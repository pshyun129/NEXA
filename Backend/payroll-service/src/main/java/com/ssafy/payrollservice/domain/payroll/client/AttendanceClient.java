package com.ssafy.payrollservice.domain.payroll.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ssafy.payrollservice.global.exception.BusinessException;
import com.ssafy.payrollservice.global.exception.ErrorCode;
import com.ssafy.nexa.security.BearerTokenRelayInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * 근태 서비스 호출.
 * GET /api/v1/attn/summary?empId=&month=YYYY-MM → ApiResult{data: AttendanceSummary}
 * GET /api/v1/attn/holidays?startDate=&endDate= → ApiResult{data: [{holidayDate, name, type}]}
 */
@Component
public class AttendanceClient {

    private final RestClient restClient;

    @Autowired
    public AttendanceClient(@Value("${payroll.clients.attendance.base-url}") String baseUrl,
                            BearerTokenRelayInterceptor bearerTokenRelay,
                            @LoadBalanced RestClient.Builder loadBalancedBuilder) {
        this(loadBalancedBuilder.clone().requestFactory(timeoutRequestFactory()), baseUrl, bearerTokenRelay);
    }

    // 요청한 관리자의 access 토큰을 근태 서비스로 넘긴다 (근태 서비스가 다시 검증).
    // 테스트에서 요청 팩토리를 바꿔 끼울 수 있게 빌더를 받는다
    AttendanceClient(RestClient.Builder builder, String baseUrl, BearerTokenRelayInterceptor bearerTokenRelay) {
        this.restClient = builder.baseUrl(baseUrl).requestInterceptor(bearerTokenRelay).build();
    }

    // 근태 서비스가 응답하지 않으면 급여 생성 요청이 끝없이 걸리지 않게
    private static SimpleClientHttpRequestFactory timeoutRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(30));
        return factory;
    }

    public AttendanceSummary getSummary(Long empId, YearMonth month) {
        InternalResult<AttendanceSummary> res;
        try {
            res = restClient.get()
                    .uri("/api/v1/attn/summary?empId={empId}&month={month}", empId, month.toString())
                    .retrieve()
                    .body(new ParameterizedTypeReference<InternalResult<AttendanceSummary>>() {
                    });
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.ATTENDANCE_CALL_FAILED, "근태 서비스 호출 실패: " + e.getMessage());
        }

        if (res == null || !res.success() || res.data() == null) {
            throw new BusinessException(ErrorCode.ATTENDANCE_CALL_FAILED, "근태 서비스 응답이 비어 있거나 실패입니다");
        }
        return res.data();
    }

    /**
     * 그 달의 공휴일 (GET /api/v1/attn/holidays). 명절수당 지급 달, 지급일 판단용.
     * 응답에는 호출한 사람(관리자)의 대체휴일(type SUBSTITUTE)도 섞여 오므로 회사 공휴일만 쓸 때는 {@link Holiday#isCompanyWide()}로 거른다
     */
    public List<Holiday> getHolidays(YearMonth month) {
        InternalResult<List<Holiday>> res;
        try {
            res = restClient.get()
                    .uri("/api/v1/attn/holidays?startDate={from}&endDate={to}", month.atDay(1), month.atEndOfMonth())
                    .retrieve()
                    .body(new ParameterizedTypeReference<InternalResult<List<Holiday>>>() {
                    });
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.ATTENDANCE_CALL_FAILED, "근태 서비스 호출 실패: " + e.getMessage());
        }

        if (res == null || !res.success() || res.data() == null) {
            throw new BusinessException(ErrorCode.ATTENDANCE_CALL_FAILED, "근태 서비스 응답이 비어 있거나 실패입니다");
        }
        return res.data();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Holiday(LocalDate holidayDate, String name, String type) {
        /** 법정·회사 지정 공휴일 (개인 대체휴일 제외) */
        public boolean isCompanyWide() {
            return !"SUBSTITUTE".equals(type);
        }
    }

    // 팀 공통 응답 형식 ApiResult{success, error, data}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InternalResult<T>(boolean success, T data) {
    }
}