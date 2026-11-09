package com.ssafy.leaveservice.client.hr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public final class UserServiceHrClient implements HrClient {
    private static final ParameterizedTypeReference<Envelope<Employee>> EMPLOYEE = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Envelope<EmployeeList>> MEMBERS = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Envelope<DutyInfo>> DUTY = new ParameterizedTypeReference<>() {};

    private final RestClient client;
    private final HrIntegrationProperties properties;

    @Override
    public boolean isAvailable() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token
                && token.isAuthenticated();
    }

    @Override
    public EmployeeInfo findEmployee(Long employeeId) {
        Employee employee = get(properties.employeeInfoUri(), employeeId, EMPLOYEE);
        return new EmployeeInfo(employee.empId(), employee.deptId(), employee.dutyId(),
                employee.positionId(), employee.gradeId(), employee.status());
    }

    @Override
    public MemberBatch findDepartmentMembers(Long departmentId) {
        EmployeeList response = get(properties.departmentMembersUri(), departmentId, MEMBERS);
        if (response.employees() == null) {
            throw unavailable();
        }
        List<Member> members = new ArrayList<>(response.employees().size());
        for (Employee employee : response.employees()) {
            if (employee == null) {
                throw unavailable();
            }
            members.add(new Member(employee.empId(), employee.deptId(), employee.status()));
        }
        return new MemberBatch(members, true);
    }

    @Override
    public DutyInfo findDuty(Long dutyId) {
        return get(properties.dutyInfoUri(), dutyId, DUTY);
    }

    @Override
    public OrganizationChain findOrganizationChain(Long employeeId) {
        return get("/api/v1/employees/{employeeId}/org-chain", employeeId, new ParameterizedTypeReference<Envelope<OrganizationChain>>() {});
    }

    private <T> T get(String uri, Long id, ParameterizedTypeReference<Envelope<T>> type) {
        if (!isAvailable() || id == null || id <= 0) {
            throw unavailable();
        }
        try {
            ResponseEntity<Envelope<T>> response = client.get().uri(uri, id).retrieve().toEntity(type);
            Envelope<T> envelope = response.getBody();
            if (response.getStatusCode().value() != 200 || envelope == null
                    || !Boolean.TRUE.equals(envelope.success()) || envelope.error() != null || envelope.data() == null) {
                throw unavailable();
            }
            return envelope.data();
        } catch (RestClientResponseException exception) {
            log.warn("User Service HR lookup failed: status={}", exception.getStatusCode().value());
            throw unavailable();
        } catch (RestClientException exception) {
            log.warn("User Service HR lookup failed: category={}", exception.getClass().getSimpleName());
            // 응답 본문과 인증 정보가 포함될 수 있는 외부 예외를 로그에 전달하지 않는다.
            throw unavailable();
        }
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.HR_VERIFICATION_UNAVAILABLE);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Envelope<T>(Boolean success, Object error, T data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Employee(Long empId, Long deptId, Long dutyId, Long gradeId, Long positionId,
                            EmploymentStatus status) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmployeeList(List<Employee> employees) {}
}
