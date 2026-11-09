package com.ssafy.leaveservice.domain.request.service;

import com.ssafy.leaveservice.domain.account.entity.LeaveAccount;
import com.ssafy.leaveservice.domain.account.repository.LeaveAccountRepository;
import com.ssafy.leaveservice.domain.ledger.entity.LeaveLedger;
import com.ssafy.leaveservice.domain.ledger.repository.LeaveLedgerRepository;
import com.ssafy.leaveservice.domain.request.dto.LeaveApprovalDecision;
import com.ssafy.leaveservice.domain.request.entity.ApprovalCreationStatus;
import com.ssafy.leaveservice.domain.request.entity.AdminCancellationStatus;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequest;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestAllocation;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestStatus;
import com.ssafy.leaveservice.domain.request.repository.LeaveApprovalRecoveryRepository;
import com.ssafy.leaveservice.domain.request.repository.LeaveRequestRepository;
import com.ssafy.leaveservice.domain.request.repository.LeaveSubmissionLockRepository;
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
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
public class LeaveApprovalSettlementService {
    public enum Outcome {
        APPLIED, ALREADY_APPLIED, AWAITING_DOCUMENT_LINK, REQUEST_NOT_FOUND, CONFLICT
    }

    private final LeaveRequestRepository requestRepository;
    private final LeaveSubmissionLockRepository locks;
    private final EntityManager entityManager;
    private final LeaveApprovalRecoveryRepository recoveryRepository;
    private final AdminCancellationReservationValidator reservationValidator;
    private final LeaveAccountRepository accountRepository;
    private final LeaveLedgerRepository ledgerRepository;

    // 신뢰할 수 있는 내부 호출 전용. 휴가 문서의 반려 후 재기안 금지가 전제다.
    public Outcome settle(LeaveApprovalDecision decision) {
        LeaveRequestStatus finalStatus = validateDecision(decision);

        // 직원 잠금 후 신청 행을 잠그고 최신 상태 확인
        Long requestId = decision.sourceId();
        Long employeeId = requestRepository.findEmployeeIdByRequestId(requestId).orElse(null);
        if (employeeId == null) {
            return Outcome.REQUEST_NOT_FOUND;
        }
        locks.lockEmployee(employeeId);
        LeaveRequest request = requestRepository.findOwnedForUpdate(requestId, employeeId).orElse(null);
        if (request == null) {
            return Outcome.REQUEST_NOT_FOUND;
        }
        entityManager.refresh(request, LockModeType.PESSIMISTIC_WRITE);
        if (!Objects.equals(request.getEmployeeId(), employeeId)
                || !decision.requestKey().equals(request.getApprovalRequestId())) {
            return Outcome.CONFLICT;
        }
        if (request.getStatus() == LeaveRequestStatus.SUBMITTING && request.getApprovalDocId() == null) {
            return Outcome.AWAITING_DOCUMENT_LINK;
        }
        if (request.getApprovalCreationStatus() != ApprovalCreationStatus.LINKED
                || !decision.documentId().equals(request.getApprovalDocId())) {
            return Outcome.CONFLICT;
        }
        if (request.getStatus() == finalStatus) {
            return Outcome.ALREADY_APPLIED;
        }
        if (request.getStatus() != LeaveRequestStatus.SUBMITTED) {
            return Outcome.CONFLICT;
        }
        // CANCEL 이벤트에 행위자와 관리자 취소 UUID가 없어 진행 중인 취소와 구분할 수 없다.
        if (finalStatus == LeaveRequestStatus.WITHDRAWN
                && (request.getAdminCancelStatus() == AdminCancellationStatus.IN_PROGRESS
                    || request.getAdminCancelStatus() == AdminCancellationStatus.UNKNOWN)) {
            return Outcome.CONFLICT;
        }

        // 최초 배분과 미정산 예약을 검증. 장부량 >= 예약량 >= 정산량이면 두 정산 모두 안전하다.
        recoveryRepository.lockReservationRows(requestId, employeeId);
        OffsetDateTime asOf = locks.currentTime();
        List<LeaveRequestAllocation> allocations = reservationValidator.findReleasableAllocations(request, asOf);

        // 원장 추가와 신청 상태 전이를 함께 커밋
        for (LeaveRequestAllocation allocation : allocations) {
            LeaveAccount account = accountRepository.getReferenceById(allocation.getAccountId());
            LeaveLedger settlement = LeaveLedger.settleApprovalReservation(account, allocation, finalStatus, asOf);
            ledgerRepository.save(settlement);
        }
        request.applyFinalApprovalDecision(finalStatus, asOf);
        requestRepository.flush();
        return Outcome.APPLIED;
    }

    private LeaveRequestStatus validateDecision(LeaveApprovalDecision decision) {
        if (decision == null || decision.eventVersion() != 1
                || !"DOCUMENT_COMPLETED".equals(decision.eventType())
                || !"LEAVE".equals(decision.formType()) || !"LEAVE".equals(decision.sourceType())
                || decision.sourceId() == null || decision.sourceId() <= 0
                || decision.documentId() == null || decision.documentId() <= 0 || decision.requestKey() == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if ("APPROVED".equals(decision.status())) {
            return LeaveRequestStatus.APPROVED;
        }
        if ("REJECTED".equals(decision.status())) {
            return LeaveRequestStatus.REJECTED;
        }
        if ("CANCEL".equals(decision.status())) {
            return LeaveRequestStatus.WITHDRAWN;
        }
        throw new BusinessException(ErrorCode.INVALID_INPUT);
    }
}
