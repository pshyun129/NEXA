package com.ssafy.payrollservice.domain.payroll.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ssafy.nexa.security.BearerTokenRelayInterceptor;
import com.ssafy.payrollservice.global.exception.BusinessException;
import com.ssafy.payrollservice.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

/**
 * user-service 사원 조회.
 * GET /api/v1/employees/{employeeId} → ApiResult{data: 사원 정보}
 */
@Component
public class UserClient {

    private final RestClient restClient;

    @Autowired
    public UserClient(@Value("${payroll.clients.user.base-url}") String baseUrl,
                      BearerTokenRelayInterceptor bearerTokenRelay) {
        this(RestClient.builder().requestFactory(timeoutRequestFactory()), baseUrl, bearerTokenRelay);
    }

    // 요청한 사원(명세서 생성은 관리자)의 access 토큰을 user-service로 넘긴다 (user-service가 다시 검증).
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

    /** 사원 정보. 없는 사원이면 BusinessException(EMPLOYEE_NOT_FOUND), 호출 실패는 USER_CALL_FAILED */
    public EmployeeInfo getEmployee(Long empId) {
        InternalResult<EmployeeInfo> res;
        try {
            res = restClient.get()
                    .uri("/api/v1/employees/{employeeId}", empId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<InternalResult<EmployeeInfo>>() {
                    });
        } catch (HttpClientErrorException.NotFound e) {
            throw new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND);
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
}
