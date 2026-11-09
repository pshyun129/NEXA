package com.ssafy.attendanceservice.domain.attendance.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ssafy.attendanceservice.global.exception.BusinessException;
import com.ssafy.attendanceservice.global.exception.ErrorCode;
import com.ssafy.nexa.security.BearerTokenRelayInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * user-service 사원·직책·조직 조회 (요청한 사원의 토큰으로).
 * GET /api/v1/employees?deptId= → ApiResult{data: {employees: [...]}}. deptId가 없으면 전체 (퇴사자 포함)
 */
@Component
public class UserClient {

    private final RestClient restClient;

    @Autowired
    public UserClient(@Value("${attendance.clients.user.base-url}") String baseUrl,
                      BearerTokenRelayInterceptor bearerTokenRelay,
                      @LoadBalanced RestClient.Builder loadBalancedBuilder) {
        this(loadBalancedBuilder.clone().requestFactory(timeoutRequestFactory()), baseUrl, bearerTokenRelay);
    }

    // 요청한 사원의 access 토큰을 user-service로 넘긴다 (user-service가 다시 검증).
    // 테스트에서 요청 팩토리를 바꿔 끼울 수 있게 빌더를 받는다
    UserClient(RestClient.Builder builder, String baseUrl, BearerTokenRelayInterceptor bearerTokenRelay) {
        this.restClient = builder.baseUrl(baseUrl).requestInterceptor(bearerTokenRelay).build();
    }

    private static SimpleClientHttpRequestFactory timeoutRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }

    /** 사원 목록. deptId가 null이면 전체 */
    public List<EmployeeInfo> getEmployees(Long deptId) {
        EmployeeList list = get(uri -> uri.path("/api/v1/employees")
                        .queryParamIfPresent("deptId", Optional.ofNullable(deptId))
                        .build(),
                new ParameterizedTypeReference<InternalResult<EmployeeList>>() {
                });
        if (list.employees() == null) {
            throw new BusinessException(ErrorCode.USER_CALL_FAILED, "user-service 응답에 사원 목록이 없습니다");
        }
        return list.employees();
    }

    /** 직책. GET /api/v1/duties/{dutyId} → {dutyId, name} */
    public DutyInfo getDuty(Long dutyId) {
        return get(uri -> uri.path("/api/v1/duties/{dutyId}").build(dutyId),
                new ParameterizedTypeReference<InternalResult<DutyInfo>>() {
                });
    }

    /**
     * 사원의 소속 부서부터 최상위 부서까지의 부서 ID (소속 부서가 첫 번째).
     * GET /api/v1/employees/{employeeId}/org-chain → {chain: [{deptId, ...}]}
     */
    public List<Long> getOrgChainDeptIds(Long empId) {
        OrgChain orgChain = get(uri -> uri.path("/api/v1/employees/{employeeId}/org-chain").build(empId),
                new ParameterizedTypeReference<InternalResult<OrgChain>>() {
                });
        if (orgChain.chain() == null) {
            throw new BusinessException(ErrorCode.USER_CALL_FAILED, "user-service 응답에 부서 체인이 없습니다");
        }
        return orgChain.chain().stream().map(OrgChain.OrgUnit::deptId).toList();
    }

    /** GET 호출 후 ApiResult의 data. 실패하거나 비어 있으면 BusinessException(USER_CALL_FAILED) */
    private <T> T get(Function<UriBuilder, URI> uri, ParameterizedTypeReference<InternalResult<T>> type) {
        InternalResult<T> res;
        try {
            res = restClient.get().uri(uri).retrieve().body(type);
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.USER_CALL_FAILED, "user-service 호출 실패: " + e.getMessage());
        }

        if (res == null || !res.success() || res.data() == null) {
            throw new BusinessException(ErrorCode.USER_CALL_FAILED, "user-service 응답이 비어 있거나 실패입니다");
        }
        return res.data();
    }

    // 팀 공통 응답 형식 ApiResult{success, error, data}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InternalResult<T>(boolean success, T data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmployeeList(List<EmployeeInfo> employees) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DutyInfo(Long dutyId, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrgChain(List<OrgUnit> chain) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record OrgUnit(Long deptId) {
        }
    }
}
