package com.ssafy.approval_service.global.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.util.List;

/**
 * user-service 사원 API. 요청한 사용자의 access 토큰을 Authorization: Bearer 로 넘겨 호출한다
 * (UserServiceClientConfig). 응답은 결재선 검증에 필요한 값(사원 ID, 재적 상태)만 받는다.
 */
@HttpExchange("/api/v1/employees")
public interface UserServiceClient {

    /** 여러 사원을 한 번에 조회. 없는 ID는 결과에서 빠진다 (user-service 최대 100개) */
    @GetExchange
    EmployeeListResult getEmployees(@RequestParam("ids") List<Long> ids);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmployeeListResult(EmployeeList data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmployeeList(List<EmployeeStatus> employees) {
    }

    /** status: EMPLOYED(재직) / ON_LEAVE(휴직) / TERMINATED(퇴직). 값이 늘어도 깨지지 않게 문자열로 받는다 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmployeeStatus(Long empId, String status) {
    }

    /** 사원의 소속 부서부터 최상위 부서까지의 체인과 각 단계의 부서장 (결재선 자동 결정용). 없는 사원이면 404 */
    @GetExchange("/{employeeId}/org-chain")
    OrgChainResult getOrgChain(@PathVariable("employeeId") Long employeeId);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrgChainResult(OrgChain data) {
    }

    /** chain[0] 이 소속 부서, 마지막이 최상위 부서 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrgChain(Long employeeId, Long deptId, List<OrgUnit> chain) {
    }

    /**
     * deptType: COMPANY(회사) / DIVISION(본부) / TEAM(팀). 값이 늘어도 깨지지 않게 문자열로 받는다.
     * headEmpId: 재직 중인 부서장. 공석이면 null (user-service 가 휴직·퇴직 부서장도 null 로 준다)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrgUnit(Long deptId, String deptName, String deptType, Long headEmpId) {
    }
}
