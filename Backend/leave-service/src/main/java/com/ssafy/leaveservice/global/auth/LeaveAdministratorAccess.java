package com.ssafy.leaveservice.global.auth;

import com.ssafy.leaveservice.client.hr.HrClient;
import com.ssafy.leaveservice.client.hr.HrIntegrationProperties;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

@Component
@RequiredArgsConstructor
public class LeaveAdministratorAccess {
    private final HrClient hrClient;
    private final HrIntegrationProperties properties;
    private final ThreadLocal<Proof> activeProof = new ThreadLocal<>();

    public boolean isAdministrator(Long employeeId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Proof proof = activeProof.get();
        if (proof != null && proof.authentication() == authentication && Objects.equals(proof.employeeId(), employeeId)) {
            return true;
        }
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()
                || employeeId == null || !String.valueOf(employeeId).equals(token.getToken().getSubject())) {
            return false;
        }
        // 외부 검증은 업무 트랜잭션과 직원 잠금을 획득하기 전에 완료한다.
        if (TransactionSynchronizationManager.isActualTransactionActive() || !properties.enabled()
                || properties.jwtClaims() == null || properties.jwtClaims().dutyId() == null
                || properties.jwtClaims().dutyId().isBlank() || !hrClient.isAvailable()) {
            throw new BusinessException(ErrorCode.HR_VERIFICATION_UNAVAILABLE);
        }
        if (java.util.Set.of("sub", "role", "type", "iss", "aud", "iat", "exp", "nbf", "jti")
                .contains(properties.jwtClaims().dutyId())) {
            throw new BusinessException(ErrorCode.HR_VERIFICATION_UNAVAILABLE);
        }
        Object claim = token.getToken().getClaim(properties.jwtClaims().dutyId());
        Long dutyId = parseDutyId(claim);
        try {
            HrClient.EmployeeInfo employee = hrClient.findEmployee(employeeId);
            if (employee == null || !employeeId.equals(employee.employeeId()) || employee.status() == null
                    || employee.dutyId() == null || employee.dutyId() <= 0) {
                throw new BusinessException(ErrorCode.HR_VERIFICATION_UNAVAILABLE);
            }
            if (employee.status() != HrClient.EmploymentStatus.EMPLOYED || !dutyId.equals(employee.dutyId())) {
                return false;
            }
            HrClient.DutyInfo duty = hrClient.findDuty(employee.dutyId());
            if (duty == null || !employee.dutyId().equals(duty.dutyId()) || duty.name() == null || duty.name().isBlank()) {
                throw new BusinessException(ErrorCode.HR_VERIFICATION_UNAVAILABLE);
            }
            return "휴가관리자".equals(duty.name());
        } catch (RuntimeException failure) {
            throw new BusinessException(ErrorCode.HR_VERIFICATION_UNAVAILABLE);
        }
    }

    private Long parseDutyId(Object claim) {
        try {
            if (claim == null || !claim.toString().matches("[1-9][0-9]*")) {
                throw new NumberFormatException();
            }
            return Long.valueOf(claim.toString());
        } catch (NumberFormatException failure) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    public Object authorize(EmployeeAccess employeeAccess, org.aopalliance.intercept.MethodInvocation invocation) throws Throwable {
        return authorize(employeeAccess, invocation::proceed);
    }

    public Object authorize(EmployeeAccess employeeAccess, AuthorizedOperation operation) throws Throwable {
        Long employeeId = employeeAccess.currentEmployeeId();
        if (employeeId == null || employeeId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (!employeeAccess.isAdmin(employeeId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        Proof previous = activeProof.get();
        activeProof.set(new Proof(SecurityContextHolder.getContext().getAuthentication(), employeeId));
        try {
            return operation.proceed();
        } finally {
            if (previous == null) {
                activeProof.remove();
            } else {
                activeProof.set(previous);
            }
        }
    }

    private record Proof(Authentication authentication, Long employeeId) {}

    @FunctionalInterface
    public interface AuthorizedOperation {
        Object proceed() throws Throwable;
    }
}
