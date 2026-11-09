package com.ssafy.leaveservice.domain.request.service;

import com.ssafy.leaveservice.client.approval.withdrawal.ApprovalWithdrawalCommand;
import com.ssafy.leaveservice.domain.account.repository.LeaveAccountRepository;
import com.ssafy.leaveservice.domain.ledger.entity.LeaveLedger;
import com.ssafy.leaveservice.domain.ledger.repository.LeaveLedgerRepository;
import com.ssafy.leaveservice.domain.request.dto.AdminCancellationAttempt;
import com.ssafy.leaveservice.domain.request.dto.AdminLeaveCancellationResponse;
import com.ssafy.leaveservice.domain.request.entity.AdminCancellationStatus;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequest;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestAllocation;
import com.ssafy.leaveservice.domain.request.repository.LeaveApprovalRecoveryRepository;
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

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@com.ssafy.leaveservice.global.auth.LeaveAdministratorOnly
@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
public class AdminLeaveCancellationTransactionService {
    private final EmployeeAccess employeeAccess;
    private final EntityManager entityManager;
    private final LeaveRequestRepository requestRepository;
    private final LeaveSubmissionLockRepository locks;
    private final LeaveApprovalRecoveryRepository recoveryRepository;
    private final AdminCancellationReservationValidator reservationValidator;
    private final LeaveLedgerRepository ledgerRepository;
    private final LeaveAccountRepository accountRepository;

    public AdminCancellationAttempt start(Long requestId, String reason) {
        // 관리자 인증과 요청 형식 확인
        Long administratorId = requireAdministrator();
        if (reason == null || reason.isBlank() || reason.length() > 2000) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        // 대상 직원과 신청 잠금 후 최초 취소 의도 확인
        LeaveRequest request = lockRequest(requestId);
        if (request.getAdminCancelStatus() != null) {
            validateIntent(request, administratorId, reason);
            return AdminCancellationAttempt.from(request, false);
        }
        request.requireSubmittedForAdminCancellation();

        // 알려진 원장 오류는 원격 철회 전에 거부하고, 최종 반영 시 다시 검증한다.
        recoveryRepository.lockReservationRows(requestId, request.getEmployeeId());
        OffsetDateTime asOf = locks.currentTime();
        reservationValidator.findReleasableAllocations(request, asOf);

        // 외부 호출 시작 직전 마커를 별도 트랜잭션으로 커밋
        UUID operationId = UUID.nameUUIDFromBytes(("nexa:leave:admin-cancel:" + requestId).getBytes(StandardCharsets.UTF_8));
        request.startAdminCancellation(operationId, administratorId, reason, asOf);
        requestRepository.flush();
        return AdminCancellationAttempt.from(request, true);
    }

    public AdminLeaveCancellationResponse complete(ApprovalWithdrawalCommand command) {
        // 저장된 관리자·사유·UUID·원본 문서와 최신 신청 상태 재검증
        LeaveRequest request = lockCommand(command);
        if (request.getAdminCancelStatus() == AdminCancellationStatus.COMPLETED) {
            return AdminLeaveCancellationResponse.from(request);
        }
        request.requireSubmittedForAdminCancellation();
        requirePending(request);

        // 최초 배분과 현재 예약을 다시 잠그고 전체 검증
        recoveryRepository.lockReservationRows(request.getRequestId(), request.getEmployeeId());
        OffsetDateTime asOf = locks.currentTime();
        List<LeaveRequestAllocation> allocations = reservationValidator.findReleasableAllocations(request, asOf);

        // RELEASE와 최종 취소 감사 정보를 원자적으로 저장
        for (LeaveRequestAllocation allocation : allocations) {
            LeaveLedger release = LeaveLedger.releaseForAdminCancellation(
                    accountRepository.getReferenceById(allocation.getAccountId()), allocation, asOf
            );
            ledgerRepository.save(release);
        }
        request.completeAdminCancellation(asOf);
        requestRepository.flush();
        return AdminLeaveCancellationResponse.from(request);
    }

    public AdminLeaveCancellationResponse recordUnknown(ApprovalWithdrawalCommand command) {
        LeaveRequest request = lockCommand(command);
        if (isTerminal(request)) {
            return AdminLeaveCancellationResponse.from(request);
        }
        // 접수·타임아웃·응답 유실은 예약을 유지하고 조회 가능한 UUID를 보존
        request.recordAdminCancellationUnknown(locks.currentTime());
        return AdminLeaveCancellationResponse.from(request);
    }

    public AdminLeaveCancellationResponse confirmNotWithdrawn(ApprovalWithdrawalCommand command) {
        LeaveRequest request = lockCommand(command);
        if (isTerminal(request)) {
            return AdminLeaveCancellationResponse.from(request);
        }
        // 원격에서 미철회를 확정한 경우에만 FAILED 기록, 자동 재호출 없음
        request.failAdminCancellation(locks.currentTime());
        return AdminLeaveCancellationResponse.from(request);
    }

    private Long requireAdministrator() {
        Long administratorId = employeeAccess.currentEmployeeId();
        if (administratorId == null || administratorId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (!employeeAccess.isAdmin(administratorId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        return administratorId;
    }

    private LeaveRequest lockRequest(Long requestId) {
        if (requestId == null || requestId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        Long employeeId = requestRepository.findEmployeeIdByRequestId(requestId).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND)
        );
        locks.lockEmployee(employeeId);
        LeaveRequest request = requestRepository.findForAdminCancellation(requestId).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND)
        );
        entityManager.refresh(request, LockModeType.PESSIMISTIC_WRITE);
        if (!Objects.equals(request.getEmployeeId(), employeeId)) {
            throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
        }
        return request;
    }

    private LeaveRequest lockCommand(ApprovalWithdrawalCommand command) {
        Long administratorId = requireAdministrator();
        if (command == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        LeaveRequest request = lockRequest(command.requestId());
        validateIntent(request, administratorId, command.reason());
        if (!Objects.equals(request.getAdminCancelRequestId(), command.withdrawalRequestId())
                || !Objects.equals(request.getApprovalDocId(), command.documentId())
                || !Objects.equals(request.getEmployeeId(), command.employeeId())
                || !Objects.equals(administratorId, command.administratorId())) {
            throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
        }
        return request;
    }

    private void validateIntent(LeaveRequest request, Long administratorId, String reason) {
        if (!Objects.equals(request.getAdminCancelRequestedBy(), administratorId)
                || !Objects.equals(request.getAdminCancelReason(), reason)) {
            throw new BusinessException(ErrorCode.LEAVE_CANCELLATION_CONFLICT);
        }
    }

    private void requirePending(LeaveRequest request) {
        if (request.getAdminCancelStatus() != AdminCancellationStatus.IN_PROGRESS
                && request.getAdminCancelStatus() != AdminCancellationStatus.UNKNOWN) {
            throw new BusinessException(ErrorCode.LEAVE_CANCELLATION_NOT_ALLOWED);
        }
    }

    private boolean isTerminal(LeaveRequest request) {
        return request.getAdminCancelStatus() == AdminCancellationStatus.COMPLETED
                || request.getAdminCancelStatus() == AdminCancellationStatus.FAILED;
    }
}
