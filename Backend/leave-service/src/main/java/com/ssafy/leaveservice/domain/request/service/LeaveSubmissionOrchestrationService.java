package com.ssafy.leaveservice.domain.request.service;

import com.ssafy.leaveservice.client.approval.ApprovalClient;
import com.ssafy.leaveservice.client.approval.ApprovalCreateCommand;
import com.ssafy.leaveservice.client.approval.ApprovalCreationResult;
import com.ssafy.leaveservice.client.approval.ApprovalProperties;
import com.ssafy.leaveservice.domain.request.dto.LeaveApprovalCallStartResult;
import com.ssafy.leaveservice.domain.request.dto.LeaveRequestPreviewRequest;
import com.ssafy.leaveservice.domain.request.dto.LeaveSubmissionResult;
import com.ssafy.leaveservice.domain.request.dto.MyLeaveRequestDetailResponse;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestStatus;
import com.ssafy.leaveservice.domain.request.entity.ApprovalCreationStatus;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LeaveSubmissionOrchestrationService {
    private final EmployeeAccess employeeAccess;
    private final ApprovalProperties approvalProperties;
    private final ApprovalClient approvalClient;
    private final LeaveSubmissionService submissionService;
    private final LeaveApprovalResultService approvalResultService;
    private final MyLeaveRequestService myLeaveRequestService;

    @Transactional(propagation = Propagation.NEVER)
    public LeaveSubmissionResult submit(String submissionKey, LeaveRequestPreviewRequest request) {
        // 본인 인증 후 미연동 상태는 T1 실행 전에 차단
        Long employeeId = employeeAccess.currentEmployeeId();
        if (employeeId == null || employeeId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (!approvalProperties.enabled() || !approvalClient.isAvailable()) {
            throw new BusinessException(ErrorCode.APPROVAL_INTEGRATION_UNAVAILABLE);
        }

        // 기존 T1에서 입력·키·멱등성·공휴일·최신 정책·배분·예약을 검증하고 커밋
        LeaveSubmissionResult submission = submissionService.submit(submissionKey, request);
        if (submission.status() != LeaveRequestStatus.SUBMITTING) {
            return submission;
        }

        // 최초 호출자만 T2 시작 마커 커밋 후 외부 생성 단계로 진행
        LeaveApprovalCallStartResult started = approvalResultService.startCall(submission.requestId(), submission.approvalRequestId());
        if (!started.callRequired()) {
            LeaveSubmissionResult existing = started.request();
            if (approvalClient.supportsRecovery() && existing.status() == LeaveRequestStatus.SUBMITTING
                    && (existing.approvalCreationStatus() == ApprovalCreationStatus.UNKNOWN
                    || existing.approvalCreationStatus() == ApprovalCreationStatus.IN_PROGRESS)) {
                return recoverSubmission(existing);
            }
            return existing;
        }
        return callApproval(started, false);
    }

    private LeaveSubmissionResult recoverSubmission(LeaveSubmissionResult existing) {
        // 우선 기존 UUID로 조회한다. 404를 미생성 확정 또는 보상 신호로 사용하지 않는다.
        ApprovalCreationResult found;
        try {
            found = approvalClient.recover(existing);
        } catch (RuntimeException exception) {
            return existing;
        }
        boolean lookupMiss = found != null && existing.approvalRequestId().equals(found.approvalRequestId())
                && found.outcome() == ApprovalCreationResult.Outcome.LOOKUP_NOT_FOUND;
        if (lookupMiss && approvalClient.supportsIdempotentResume()) {
            // 짧은 로컬 트랜잭션에서 재개 권한만 커밋하고 HTTP는 그 밖에서 실행한다.
            LeaveApprovalCallStartResult resumed = approvalResultService.startRecoveryCall(
                    existing.requestId(), existing.approvalRequestId());
            return resumed.callRequired() ? callApproval(resumed, true) : resumed.request();
        }
        if (found == null || found.outcome() == ApprovalCreationResult.Outcome.UNKNOWN || lookupMiss) {
            // 조회 실패가 현재 진행 중인 생성 호출의 상태나 시각을 바꾸면 안 된다.
            return existing;
        }
        return completeSubmission(existing, found, null);
    }

    private LeaveSubmissionResult callApproval(LeaveApprovalCallStartResult started, boolean resume) {
        LeaveSubmissionResult submission = started.request();
        MyLeaveRequestDetailResponse storedRequest = myLeaveRequestService.getMyRequest(submission.requestId());
        if (storedRequest.status() != LeaveRequestStatus.SUBMITTING || storedRequest.approvalDocId() != null) {
            throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
        }
        ApprovalCreateCommand command = ApprovalCreateCommand.from(started.request(), storedRequest);

        // DB 트랜잭션 밖에서 호출하며 예외·불완전 결과는 미생성으로 단정하지 않음
        ApprovalCreationResult result;
        try {
            result = resume ? approvalClient.resume(command) : approvalClient.create(command);
        } catch (RuntimeException exception) {
            log.warn("Approval outcome unknown: requestId={}, traceId={}, cause={}",
                    submission.requestId(), submission.approvalRequestId(), exception.getClass().getSimpleName());
            return recordUnknown(submission.requestId(), submission.approvalRequestId(),
                    "APPROVAL_CLIENT_EXCEPTION", started.attemptCount());
        }

        // T2의 성공·확정 실패·결과 불명 로직을 별도 트랜잭션으로 연결
        return completeSubmission(started.request(), result, started.attemptCount());
    }

    private LeaveSubmissionResult completeSubmission(
            LeaveSubmissionResult submission,
            ApprovalCreationResult result,
            Integer attemptCount
    ) {
        Long requestId = submission.requestId();
        UUID approvalRequestId = submission.approvalRequestId();
        if (result == null || result.outcome() == null || !approvalRequestId.equals(result.approvalRequestId())) {
            return recordUnknown(requestId, approvalRequestId, "APPROVAL_RESULT_UNVERIFIABLE", attemptCount);
        }
        if (result.outcome() == ApprovalCreationResult.Outcome.SUBMITTED_CONFIRMED
                && result.documentId() != null && result.documentId() > 0) {
            // 실제 제출 확인만 성공이다. DRAFT 생성만으로 SUBMITTED를 확정하지 않는다.
            return approvalResultService.linkCreatedDocument(requestId, approvalRequestId, result.documentId());
        }
        if (result.outcome() == ApprovalCreationResult.Outcome.NOT_CREATED_CONFIRMED && result.documentId() == null) {
            return approvalResultService.confirmNotCreated(requestId, approvalRequestId, "APPROVAL_NOT_CREATED_CONFIRMED");
        }
        String reason = result.reason() == null ? "APPROVAL_SUBMISSION_UNCONFIRMED" : result.reason();
        LeaveSubmissionResult pending = recordUnknown(requestId, approvalRequestId, reason, attemptCount);
        if (result.outcome() == ApprovalCreationResult.Outcome.CONFLICT
                && pending.approvalCreationStatus() != ApprovalCreationStatus.LINKED) {
            throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
        }
        return pending;
    }

    private LeaveSubmissionResult recordUnknown(Long requestId, UUID approvalRequestId, String reason, Integer attemptCount) {
        if (approvalClient.supportsRecovery()) {
            if (attemptCount != null) {
                return approvalResultService.recordUnknownAfterHttp(requestId, approvalRequestId, reason, attemptCount);
            }
            return approvalResultService.recordUnknownAfterHttp(requestId, approvalRequestId, reason);
        }
        return approvalResultService.recordUnknown(requestId, approvalRequestId, reason);
    }
}
