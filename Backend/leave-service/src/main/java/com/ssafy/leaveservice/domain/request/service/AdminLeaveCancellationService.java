package com.ssafy.leaveservice.domain.request.service;

import com.ssafy.leaveservice.client.attendance.AttendanceRevertClient;
import com.ssafy.leaveservice.client.attendance.AttendanceRevertCommand;
import com.ssafy.leaveservice.client.attendance.AttendanceRevertProperties;
import com.ssafy.leaveservice.client.attendance.AttendanceRevertResult;
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
    private final AttendanceRevertProperties properties;
    private final AttendanceRevertClient client;
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
        if (!properties.enabled() || !properties.contractVerified() || !client.isAvailable()) {
            throw new BusinessException(ErrorCode.ATTENDANCE_REVERT_UNAVAILABLE);
        }
        AdminCancellationAttempt attempt = transactions.start(requestId, request.reason());
        AdminCancellationStatus status = attempt.response().adminCancelStatus();
        if (status == AdminCancellationStatus.COMPLETED || status == AdminCancellationStatus.FAILED) {
            return requireSuccessfulOrPending(attempt.response());
        }

        // 최초 요청만 원복 호출, 진행 중 재요청은 기존 UUID로 검증된 복구만 수행
        AttendanceRevertCommand command = attempt.command();
        AttendanceRevertResult result;
        try {
            result = attempt.callRequired() ? client.revert(command) : client.recover(command);
        } catch (RuntimeException exception) {
            log.warn("Attendance revert outcome unknown: requestId={}, traceId={}, cause={}",
                    requestId, command.cancelRequestId(), exception.getClass().getSimpleName());
            return requireSuccessfulOrPending(transactions.recordUnknown(command));
        }

        // 신청과 UUID가 일치하는 최종 원복 결과만 로컬 취소로 반영
        AdminLeaveCancellationResponse response;
        if (result == null || result.outcome() == null
                || !Objects.equals(result.cancelRequestId(), command.cancelRequestId())
                || !Objects.equals(result.requestId(), command.requestId())) {
            response = transactions.recordUnknown(command);
        } else if (result.outcome() == AttendanceRevertResult.Outcome.REVERTED_CONFIRMED) {
            response = transactions.complete(command);
        } else if (result.outcome() == AttendanceRevertResult.Outcome.NOT_REVERTED_CONFIRMED) {
            response = transactions.confirmNotReverted(command);
        } else {
            response = transactions.recordUnknown(command);
        }
        return requireSuccessfulOrPending(response);
    }

    private AdminLeaveCancellationResponse requireSuccessfulOrPending(AdminLeaveCancellationResponse response) {
        if (response.adminCancelStatus() == AdminCancellationStatus.FAILED) {
            throw new BusinessException(ErrorCode.LEAVE_CANCELLATION_NOT_ALLOWED,
                    "근태 미원복이 확정되어 취소하지 않았습니다. 새로운 원복 호출은 자동으로 수행하지 않습니다.");
        }
        return response;
    }
}
