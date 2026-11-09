package com.ssafy.leaveservice.domain.request.service;

import com.ssafy.leaveservice.domain.account.repository.LeaveAccountRepository;
import com.ssafy.leaveservice.domain.account.repository.projection.PreviewGrantBalance;
import com.ssafy.leaveservice.domain.account.service.LeaveBalanceAmounts;
import com.ssafy.leaveservice.domain.ledger.entity.LeaveLedger;
import com.ssafy.leaveservice.domain.ledger.entity.LedgerEventType;
import com.ssafy.leaveservice.domain.ledger.repository.LeaveLedgerRepository;
import com.ssafy.leaveservice.domain.request.dto.LeaveApprovalCallStartResult;
import com.ssafy.leaveservice.domain.request.dto.LeaveSubmissionResult;
import com.ssafy.leaveservice.domain.request.entity.ApprovalCreationStatus;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequest;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestAllocation;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestDate;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestStatus;
import com.ssafy.leaveservice.domain.request.repository.LeaveApprovalRecoveryRepository;
import com.ssafy.leaveservice.domain.request.repository.LeaveRequestAllocationRepository;
import com.ssafy.leaveservice.domain.request.repository.LeaveRequestRepository;
import com.ssafy.leaveservice.domain.request.repository.LeaveSubmissionLockRepository;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
public class LeaveApprovalResultService {
    private final EmployeeAccess employeeAccess;
    private final EntityManager entityManager;
    private final LeaveSubmissionLockRepository submissionLockRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final LeaveRequestAllocationRepository allocationRepository;
    private final LeaveLedgerRepository leaveLedgerRepository;
    private final LeaveAccountRepository leaveAccountRepository;
    private final LeaveApprovalRecoveryRepository recoveryRepository;

    public LeaveApprovalCallStartResult startCall(Long requestId, UUID approvalRequestId) {
        // 본인 신청 잠금 및 추적 UUID 확인
        LeaveRequest request = lockOwnedRequest(requestId, approvalRequestId);
        if (request.getApprovalCreationStatus() != ApprovalCreationStatus.NOT_SENT) {
            // UNKNOWN/IN_PROGRESS는 UUID 결과 조회 전까지 다시 생성 호출하지 않는다.
            return new LeaveApprovalCallStartResult(LeaveSubmissionResult.from(request), false);
        }

        // 최초 호출 직전 마커와 시도 횟수를 별도 트랜잭션으로 확정
        request.startApprovalCall(submissionLockRepository.currentTime());
        return new LeaveApprovalCallStartResult(LeaveSubmissionResult.from(request), true);
    }

    public LeaveSubmissionResult linkCreatedDocument(Long requestId, UUID approvalRequestId, Long documentId) {
        // 실제 문서 ID와 본인 신청의 추적 UUID 확인
        if (documentId == null || documentId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        LeaveRequest request = lockOwnedRequest(requestId, approvalRequestId);
        if (request.getApprovalCreationStatus() == ApprovalCreationStatus.LINKED
                && Objects.equals(request.getApprovalDocId(), documentId)) {
            return LeaveSubmissionResult.from(request);
        }
        requirePending(request);
        if (leaveRequestRepository.existsByApprovalDocIdAndRequestIdNot(documentId, requestId)) {
            throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
        }

        // 예약 원장은 유지하고 문서 연결과 신청 제출을 함께 확정
        request.linkCreatedApproval(documentId, submissionLockRepository.currentTime());
        leaveRequestRepository.flush();
        return LeaveSubmissionResult.from(request);
    }

    public LeaveSubmissionResult recordUnknown(Long requestId, UUID approvalRequestId, String reason) {
        return recordUnknown(requestId, approvalRequestId, reason, false);
    }

    public LeaveSubmissionResult recordUnknownAfterHttp(Long requestId, UUID approvalRequestId, String reason) {
        return recordUnknown(requestId, approvalRequestId, reason, true);
    }

    private LeaveSubmissionResult recordUnknown(Long requestId, UUID approvalRequestId, String reason, boolean keepLinked) {
        // 늦은 HTTP 실패가 먼저 커밋된 문서 연결을 되돌리지 않도록 한다.
        validateReason(reason);
        LeaveRequest request = lockOwnedRequest(requestId, approvalRequestId);
        if (keepLinked && request.getApprovalCreationStatus() == ApprovalCreationStatus.LINKED) {
            return LeaveSubmissionResult.from(request);
        }
        requirePending(request);
        if (request.getApprovalCreationStatus() == ApprovalCreationStatus.UNKNOWN
                && Objects.equals(request.getApprovalLastError(), reason)) {
            return LeaveSubmissionResult.from(request);
        }

        // 기존 추적 UUID로 향후 결과 조회가 가능하도록 오류와 시각만 기록
        request.recordUnknownApproval(reason, submissionLockRepository.currentTime());
        return LeaveSubmissionResult.from(request);
    }

    /** 원격 서비스가 해당 UUID의 문서 미생성을 확정한 경우에만 호출한다. */
    // TODO: Approval 결과 조회 계약 확정 후, UUID의 최종 미생성을 검증한 호출부에서만 연결한다.
    public LeaveSubmissionResult confirmNotCreated(Long requestId, UUID approvalRequestId, String reason) {
        // 본인 신청과 확정 실패 사유 확인
        validateReason(reason);
        LeaveRequest request = lockOwnedRequest(requestId, approvalRequestId);
        boolean alreadyFailed = request.getStatus() == LeaveRequestStatus.CANCELLED
                && request.getApprovalCreationStatus() == ApprovalCreationStatus.FAILED;
        if (!alreadyFailed) {
            requirePending(request);
        }

        // 최초 배분과 예약 원장을 잠그고 보상 가능 수량을 모두 검증
        recoveryRepository.lockReservationRows(requestId, request.getEmployeeId());
        OffsetDateTime asOf = submissionLockRepository.currentTime();
        List<LeaveRequestAllocation> allocations = allocationRepository.findAllByRequestIdOrderByAllocationIdAsc(requestId);
        List<LeaveLedger> entries = leaveLedgerRepository.findAllByLeaveReqIdOrderByEntryIdAsc(requestId);
        validateReservationHistory(request, allocations, entries, alreadyFailed, asOf);
        if (alreadyFailed) {
            return LeaveSubmissionResult.from(request);
        }
        validateGrantBalances(request.getEmployeeId(), allocations, asOf);

        // allocation별 RELEASE와 실패 종료를 하나의 트랜잭션으로 저장
        for (LeaveRequestAllocation allocation : allocations) {
            LeaveLedger release = LeaveLedger.release(
                    leaveAccountRepository.getReferenceById(allocation.getAccountId()), allocation, asOf);
            leaveLedgerRepository.save(release);
        }
        request.cancelAfterConfirmedApprovalFailure(reason, request.getEmployeeId(), asOf);
        leaveRequestRepository.flush();
        return LeaveSubmissionResult.from(request);
    }

    private LeaveRequest lockOwnedRequest(Long requestId, UUID approvalRequestId) {
        Long employeeId = employeeAccess.currentEmployeeId();
        if (employeeId == null || employeeId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (requestId == null || requestId <= 0 || approvalRequestId == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        submissionLockRepository.lockEmployee(employeeId);
        LeaveRequest request = leaveRequestRepository.findOwnedForUpdate(requestId, employeeId).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND));
        // OSIV 등에서 이미 관리 중인 Entity도 잠금 후 DB 상태로 다시 읽는다.
        entityManager.refresh(request, LockModeType.PESSIMISTIC_WRITE);
        if (!approvalRequestId.equals(request.getApprovalRequestId())) {
            throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
        }
        return request;
    }

    private void requirePending(LeaveRequest request) {
        boolean pending = request.getApprovalCreationStatus() == ApprovalCreationStatus.NOT_SENT
                || request.getApprovalCreationStatus() == ApprovalCreationStatus.IN_PROGRESS
                || request.getApprovalCreationStatus() == ApprovalCreationStatus.UNKNOWN;
        if (request.getStatus() != LeaveRequestStatus.SUBMITTING || request.getApprovalDocId() != null || !pending) {
            throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
        }
    }

    private void validateReason(String reason) {
        // 외부 응답 본문이나 개인정보 대신 호출부가 분류한 짧은 진단 사유를 전달한다.
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    private void validateReservationHistory(
            LeaveRequest request,
            List<LeaveRequestAllocation> allocations,
            List<LeaveLedger> entries,
            boolean alreadyFailed,
            OffsetDateTime asOf
    ) {
        Map<Long, List<LeaveLedger>> entriesByAllocation = new HashMap<>();
        for (LeaveLedger entry : entries) {
            if (entry.getAllocationId() == null || entry.getCreatedAt().isAfter(asOf)) {
                throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
            }
            entriesByAllocation.computeIfAbsent(entry.getAllocationId(), ignored -> new ArrayList<>()).add(entry);
        }
        Map<Long, Long> allocatedByDate = new HashMap<>();
        long totalAllocated = 0;
        try {
            for (LeaveRequestAllocation allocation : allocations) {
                if (!Objects.equals(allocation.getRequestId(), request.getRequestId())
                        || !Objects.equals(allocation.getEmployeeId(), request.getEmployeeId())
                        || allocation.getAllocatedMinutes() <= 0) {
                    throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
                }
                List<LeaveLedger> allocationEntries = entriesByAllocation.remove(allocation.getAllocationId());
                validateAllocationEntries(allocation, allocationEntries, alreadyFailed);
                totalAllocated = Math.addExact(totalAllocated, allocation.getAllocatedMinutes());
                allocatedByDate.merge(allocation.getRequestDateId(), (long) allocation.getAllocatedMinutes(), Math::addExact);
            }
            long totalDeductMinutes = 0;
            for (LeaveRequestDate date : request.getDates()) {
                Long allocatedMinutes = allocatedByDate.remove(date.getRequestDateId());
                if ((allocatedMinutes == null ? 0 : allocatedMinutes) != date.getDeductMinutes()) {
                    throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
                }
                totalDeductMinutes = Math.addExact(totalDeductMinutes, date.getDeductMinutes());
            }
            if (!entriesByAllocation.isEmpty() || !allocatedByDate.isEmpty()
                    || totalAllocated != request.getTotalDeductMinutes() || totalDeductMinutes != totalAllocated) {
                throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
            }
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
        }
    }

    private void validateAllocationEntries(
            LeaveRequestAllocation allocation,
            List<LeaveLedger> entries,
            boolean alreadyFailed
    ) {
        if (entries == null || entries.size() != (alreadyFailed ? 2 : 1)) {
            throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
        }
        int reserveCount = 0;
        int releaseCount = 0;
        for (LeaveLedger entry : entries) {
            boolean sameOwner = Objects.equals(entry.getAccount().getAccountId(), allocation.getAccountId())
                    && Objects.equals(entry.getGrantId(), allocation.getGrantId())
                    && Objects.equals(entry.getLeaveReqId(), allocation.getRequestId())
                    && entry.getQuantityDelta() == 0;
            if (!sameOwner) {
                throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
            }
            if (entry.getEventType() == LedgerEventType.RESERVE
                    && entry.getReservedDelta() == allocation.getAllocatedMinutes()) {
                reserveCount++;
            } else if (alreadyFailed && entry.getEventType() == LedgerEventType.RELEASE
                    && entry.getReservedDelta() == -allocation.getAllocatedMinutes()
                    && entry.getIdempotencyKey().equals("leave:release:allocation:" + allocation.getAllocationId())) {
                releaseCount++;
            } else {
                // 다른 경로의 사용·해제나 부분 보상은 임의로 보정하지 않는다.
                throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
            }
        }
        if (reserveCount != 1 || releaseCount != (alreadyFailed ? 1 : 0)) {
            throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
        }
    }

    private void validateGrantBalances(Long employeeId, List<LeaveRequestAllocation> allocations, OffsetDateTime asOf) {
        if (allocations.isEmpty()) {
            return;
        }
        Map<Long, Long> releaseByGrant = new HashMap<>();
        try {
            for (LeaveRequestAllocation allocation : allocations) {
                releaseByGrant.merge(allocation.getGrantId(), (long) allocation.getAllocatedMinutes(), Math::addExact);
            }
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
        }
        // 만료·미래 발생분도 최초 예약 소속이면 해제 대상이며 현재 FEFO를 재실행하지 않는다.
        List<PreviewGrantBalance> balances = leaveAccountRepository.findRecordedGrantBalances(employeeId, asOf);
        for (PreviewGrantBalance balance : balances) {
            Long releaseMinutes = releaseByGrant.remove(balance.getGrantId());
            if (releaseMinutes == null) {
                continue;
            }
            LeaveBalanceAmounts amounts = LeaveBalanceAmounts.from(balance.getQuantityMinutes(), balance.getReservedMinutes());
            if (amounts.reservedMinutes() < releaseMinutes) {
                throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
            }
        }
        if (!releaseByGrant.isEmpty()) {
            throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
        }
    }
}
