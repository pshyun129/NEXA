package com.ssafy.leaveservice.domain.department.service;

import com.ssafy.leaveservice.client.hr.HrClient;
import com.ssafy.leaveservice.client.hr.HrClient.*;
import com.ssafy.leaveservice.client.hr.HrIntegrationProperties;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class DepartmentLeaveAccessService {
    private final EmployeeAccess employeeAccess;
    private final HrIntegrationProperties properties;
    private final HrClient hrClient;

    @Transactional(propagation = Propagation.NEVER)
    public List<Long> getScheduleEmployeeIds(Long departmentId) {
        // 일정은 현재 같은 부서의 재직 직원에게 공개한다.
        requireDepartmentId(departmentId);
        EmployeeInfo employee = requireCurrentEmployee();
        if (!departmentId.equals(employee.departmentId())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        return findMembers(departmentId);
    }

    @Transactional(propagation = Propagation.NEVER)
    public List<Long> getAuthorizedEmployeeIds(Long departmentId) {
        if (!properties.hasConfiguredEndpoints() || !properties.hasConfiguredClaims()) {
            throw new BusinessException(ErrorCode.HR_VERIFICATION_UNAVAILABLE);
        }
        requireDepartmentId(departmentId);
        EmployeeInfo employee = requireCurrentEmployee();

        // 잔액 조회는 최신 직책과 실제 부서 책임자 정보를 함께 검증한다.
        Jwt jwt = ((JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication()).getToken();
        if (properties.jwtClaims().dutyId() == null || properties.jwtClaims().dutyId().isBlank()) {
            throw new BusinessException(ErrorCode.HR_VERIFICATION_UNAVAILABLE);
        }
        Long tokenDutyId = positiveClaim(jwt.getClaim(properties.jwtClaims().dutyId()));
        if (employee.dutyId() == null || employee.dutyId() <= 0) {
            throw new BusinessException(ErrorCode.HR_VERIFICATION_UNAVAILABLE);
        }
        if (!tokenDutyId.equals(employee.dutyId())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        DutyInfo duty = callHr(() -> hrClient.findDuty(employee.dutyId()));
        if (duty == null || !employee.dutyId().equals(duty.dutyId()) || duty.name() == null || duty.name().isBlank()) {
            throw new BusinessException(ErrorCode.HR_VERIFICATION_UNAVAILABLE);
        }
        if (!Set.of("팀장", "본부장", "대표이사").contains(duty.name())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        OrganizationChain organization = callHr(() -> hrClient.findOrganizationChain(employee.employeeId()));
        if (organization == null || !employee.employeeId().equals(organization.employeeId())
                || !employee.departmentId().equals(organization.deptId()) || organization.chain() == null
                || organization.chain().isEmpty() || organization.chain().getFirst() == null
                || !employee.departmentId().equals(organization.chain().getFirst().deptId())) {
            throw new BusinessException(ErrorCode.HR_VERIFICATION_UNAVAILABLE);
        }
        Set<Long> departments = new HashSet<>();
        OrganizationUnit target = null;
        for (OrganizationUnit unit : organization.chain()) {
            if (unit == null || !positive(unit.deptId()) || !departments.add(unit.deptId())
                    || (unit.headEmpId() != null && !positive(unit.headEmpId()))) {
                throw new BusinessException(ErrorCode.HR_VERIFICATION_UNAVAILABLE);
            }
            if (departmentId.equals(unit.deptId())) {
                target = unit;
            }
        }
        if (target == null || !employee.employeeId().equals(target.headEmpId())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        return findMembers(departmentId);
    }

    private EmployeeInfo requireCurrentEmployee() {
        // JWT 본인 식별과 최신 재직·소속 정보를 대조한다.
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken authentication)
                || !authentication.isAuthenticated()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        Jwt jwt = authentication.getToken();
        Long employeeId = positiveClaim(jwt.getSubject());
        if (!employeeId.equals(employeeAccess.currentEmployeeId())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (!properties.enabled() || properties.employeeInfoUri() == null || properties.employeeInfoUri().isBlank()
                || properties.jwtClaims() == null || properties.jwtClaims().departmentId() == null
                || properties.jwtClaims().departmentId().isBlank() || !callHr(hrClient::isAvailable)) {
            throw new BusinessException(ErrorCode.HR_VERIFICATION_UNAVAILABLE);
        }
        if (Set.of("sub", "role", "type", "iss", "aud", "iat", "exp", "nbf", "jti")
                .contains(properties.jwtClaims().departmentId())) {
            throw new BusinessException(ErrorCode.HR_VERIFICATION_UNAVAILABLE);
        }
        Long tokenDepartmentId = positiveClaim(jwt.getClaim(properties.jwtClaims().departmentId()));
        EmployeeInfo employee = callHr(() -> hrClient.findEmployee(employeeId));
        if (employee == null || !employeeId.equals(employee.employeeId()) || !positive(employee.departmentId())
                || employee.status() == null) {
            throw new BusinessException(ErrorCode.HR_VERIFICATION_UNAVAILABLE);
        }
        if (employee.status() != EmploymentStatus.EMPLOYED || !tokenDepartmentId.equals(employee.departmentId())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        return employee;
    }

    private List<Long> findMembers(Long departmentId) {
        MemberBatch batch = callHr(() -> hrClient.findDepartmentMembers(departmentId));
        if (batch == null || !batch.complete() || batch.members() == null) {
            throw new BusinessException(ErrorCode.HR_VERIFICATION_UNAVAILABLE);
        }
        Set<Long> employeeIds = new HashSet<>();
        List<Long> included = new ArrayList<>();
        for (Member member : batch.members()) {
            if (member == null || !positive(member.employeeId()) || !departmentId.equals(member.departmentId())
                    || member.status() == null || !employeeIds.add(member.employeeId())) {
                throw new BusinessException(ErrorCode.HR_VERIFICATION_UNAVAILABLE);
            }
            if (member.status() != EmploymentStatus.TERMINATED) {
                included.add(member.employeeId());
            }
        }
        return included.stream().sorted().toList();
    }

    private void requireDepartmentId(Long departmentId) {
        if (!positive(departmentId)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    private Long positiveClaim(Object claim) {
        try {
            long value;
            if (claim instanceof String text && text.matches("[1-9][0-9]*")) {
                value = Long.parseLong(text);
            } else if (claim instanceof Byte || claim instanceof Short || claim instanceof Integer || claim instanceof Long) {
                value = ((Number) claim).longValue();
            } else if (claim instanceof BigInteger integer) {
                value = integer.longValueExact();
            } else {
                throw new BusinessException(ErrorCode.UNAUTHORIZED);
            }
            if (value <= 0) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED);
            }
            return value;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    private boolean positive(Long value) {
        return value != null && value > 0;
    }

    private <T> T callHr(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RuntimeException failure) {
            throw new BusinessException(ErrorCode.HR_VERIFICATION_UNAVAILABLE);
        }
    }
}
