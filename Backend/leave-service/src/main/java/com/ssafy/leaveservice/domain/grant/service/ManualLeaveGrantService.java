package com.ssafy.leaveservice.domain.grant.service;

import com.ssafy.leaveservice.client.hr.HrClient;
import com.ssafy.leaveservice.domain.grant.dto.ManualLeaveGrantRequest;
import com.ssafy.leaveservice.domain.grant.dto.ManualLeaveGrantResponse;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.auth.LeaveAdministratorOnly;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
@LeaveAdministratorOnly
public class ManualLeaveGrantService {
    private final EmployeeAccess employeeAccess;
    private final HrClient hrClient;
    private final Validator validator;
    private final ManualLeaveGrantTransactionService transactions;

    @Transactional(propagation = Propagation.NEVER)
    public ManualLeaveGrantResponse grant(Long employeeId, String requestKey, ManualLeaveGrantRequest request) {
        // 관리자 권한 및 지급 입력값 확인
        Long actorId = employeeAccess.currentEmployeeId();
        if (actorId == null || actorId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (!employeeAccess.isAdmin(actorId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        if (employeeId == null || employeeId <= 0 || request == null || !validator.validate(request).isEmpty()
                || requestKey == null || !requestKey.matches("[!-~]{1,128}")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        ManualLeaveGrantRequest normalized = new ManualLeaveGrantRequest(
                request.balanceTypeId(), request.grantMode(), request.grantMinutes(),
                request.validFrom().truncatedTo(ChronoUnit.MICROS),
                request.expiresOn() == null ? null : request.expiresOn().truncatedTo(ChronoUnit.MICROS), request.reason()
        );
        if (normalized.validFrom().getYear() < -4712 || normalized.validFrom().getYear() > 294275
                || (normalized.expiresOn() != null && (normalized.expiresOn().getYear() < -4712
                || normalized.expiresOn().getYear() > 294275
                || !normalized.expiresOn().isAfter(normalized.validFrom())))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        // 외부 직원 검증은 DB 트랜잭션과 직원 잠금 밖에서 수행
        if (!hrClient.isAvailable()) {
            throw new BusinessException(ErrorCode.HR_VERIFICATION_UNAVAILABLE);
        }
        HrClient.EmployeeInfo employee = hrClient.findEmployee(employeeId);
        if (employee == null || !employeeId.equals(employee.employeeId()) || employee.status() == null) {
            throw new BusinessException(ErrorCode.HR_VERIFICATION_UNAVAILABLE);
        }
        if (employee.status() != HrClient.EmploymentStatus.EMPLOYED) {
            throw new BusinessException(ErrorCode.LEAVE_GRANT_NOT_ALLOWED);
        }

        // 별도 Spring 프록시에서 계정·발생분·원장·멱등 결과를 원자적으로 저장
        return transactions.grant(employeeId, actorId, requestKey, normalized);
    }
}
