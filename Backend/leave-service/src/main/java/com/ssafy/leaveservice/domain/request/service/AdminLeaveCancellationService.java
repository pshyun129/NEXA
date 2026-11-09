package com.ssafy.leaveservice.domain.request.service;

import com.ssafy.leaveservice.client.approval.withdrawal.ApprovalWithdrawalClient;
import com.ssafy.leaveservice.client.approval.withdrawal.ApprovalWithdrawalCommand;
import com.ssafy.leaveservice.client.approval.withdrawal.ApprovalWithdrawalProperties;
import com.ssafy.leaveservice.client.approval.withdrawal.ApprovalWithdrawalResult;
import com.ssafy.leaveservice.domain.request.dto.AdminCancellationAttempt;
import com.ssafy.leaveservice.domain.request.dto.AdminLeaveCancellationRequest;
import com.ssafy.leaveservice.domain.request.dto.AdminLeaveCancellationResponse;
import com.ssafy.leaveservice.domain.request.entity.AdminCancellationStatus;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Slf4j
@com.ssafy.leaveservice.global.auth.LeaveAdministratorOnly
@Service
@RequiredArgsConstructor
public class AdminLeaveCancellationService {
    private final EmployeeAccess employeeAccess;
    private final Validator validator;
    private final ApprovalWithdrawalProperties properties;
    private final ApprovalWithdrawalClient client;
    private final AdminLeaveCancellationTransactionService transactions;

    @Transactional(propagation = Propagation.NEVER)
    public AdminLeaveCancellationResponse cancel(
            Long requestId,
            AdminLeaveCancellationRequest request
    ) {
        // 관리자 권한과 입력 확인
        Long administratorId = employeeAccess.currentEmployeeId();
        if (administratorId == null || administratorId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (!employeeAccess.isAdmin(administratorId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        if (requestId == null || requestId <= 0 || request == null || !validator.validate(request).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        // 연동 미준비 상태는 최초 취소 마커나 원장 저장 전에 차단
        if (!properties.enabled() || !client.isAvailable()) {
            throw new BusinessException(ErrorCode.APPROVAL_WITHDRAWAL_UNAVAILABLE);
        }
        AdminCancellationAttempt attempt = transactions.start(requestId, request.reason());
        AdminCancellationStatus status = attempt.response().adminCancelStatus();
        if (status == AdminCancellationStatus.COMPLETED || status == AdminCancellationStatus.FAILED) {
            return requireSuccessfulOrPending(attempt.response());
        }

        // 최초 요청만 철회 호출, 진행 중 재요청은 기존 UUID 결과 조회만 수행
        ApprovalWithdrawalCommand command = attempt.command();
        ApprovalWithdrawalResult result;
        try {
            result = attempt.callRequired() ? client.withdraw(command) : client.findByRequestId(command.withdrawalRequestId());
        } catch (RuntimeException exception) {
            log.warn("Approval withdrawal outcome unknown: requestId={}, traceId={}, cause={}",
                    requestId, command.withdrawalRequestId(), exception.getClass().getSimpleName());
            return requireSuccessfulOrPending(transactions.recordUnknown(command));
        }

        // 문서와 UUID가 일치하는 최종 철회 결과만 로컬 취소로 반영
        AdminLeaveCancellationResponse response;
        if (result == null || result.outcome() == null
                || !Objects.equals(result.withdrawalRequestId(), command.withdrawalRequestId())
                || !Objects.equals(result.documentId(), command.documentId())) {
            response = transactions.recordUnknown(command);
        } else if (result.outcome() == ApprovalWithdrawalResult.Outcome.WITHDRAWN_CONFIRMED) {
            response = transactions.complete(command);
        } else if (result.outcome() == ApprovalWithdrawalResult.Outcome.NOT_WITHDRAWN_CONFIRMED) {
            response = transactions.confirmNotWithdrawn(command);
        } else {
            response = transactions.recordUnknown(command);
        }
        return requireSuccessfulOrPending(response);
    }

    private AdminLeaveCancellationResponse requireSuccessfulOrPending(AdminLeaveCancellationResponse response) {
        if (response.adminCancelStatus() == AdminCancellationStatus.FAILED) {
            throw new BusinessException(ErrorCode.LEAVE_CANCELLATION_NOT_ALLOWED,
                    "원격 미철회가 확정되어 취소하지 않았습니다. 새로운 철회 호출은 자동으로 수행하지 않습니다.");
        }
        return response;
    }
}
