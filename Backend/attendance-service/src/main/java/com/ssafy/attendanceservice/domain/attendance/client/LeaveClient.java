package com.ssafy.attendanceservice.domain.attendance.client;

import com.ssafy.attendanceservice.domain.attendance.client.ApprovalClient.InternalResult;
import com.ssafy.attendanceservice.global.exception.BusinessException;
import com.ssafy.attendanceservice.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

/**
 * 휴가 서비스 호출 (근태 연동용 내부 API).
 * 결재 결과 이벤트를 받아서 부르므로 넘길 사용자 토큰이 없다. 내부 경로는 게이트웨이 밖에서만 열려 있다
 */
@Component
public class LeaveClient {

    private final RestClient restClient;

    @Autowired
    public LeaveClient(@Value("${attendance.clients.leave.base-url}") String baseUrl,
                       @LoadBalanced RestClient.Builder loadBalancedBuilder) {
        this(loadBalancedBuilder.clone().requestFactory(timeoutRequestFactory()), baseUrl);
    }

    // 테스트에서 요청 팩토리를 바꿔 끼울 수 있게 빌더를 받는다
    LeaveClient(RestClient.Builder builder, String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    private static SimpleClientHttpRequestFactory timeoutRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return factory;
    }

    /** 휴가 신청의 사원·유형·날짜. 실패하면 BusinessException(LEAVE_CALL_FAILED): 리스너가 재시도 후 DLT로 넘긴다 */
    public LeaveRequestDates getRequestDates(Long leaveReqId) {
        InternalResult<LeaveRequestDates> res;
        try {
            res = restClient.get()
                    .uri("/api/v1/leaves/internal/requests/{requestId}/dates", leaveReqId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<InternalResult<LeaveRequestDates>>() {
                    });
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.LEAVE_CALL_FAILED, "휴가 서비스 호출 실패: " + e.getMessage());
        }

        if (res == null || !res.success() || res.data() == null
                || res.data().employeeId() == null || res.data().dates() == null) {
            throw new BusinessException(ErrorCode.LEAVE_CALL_FAILED, "휴가 서비스 응답이 비어 있거나 실패입니다");
        }
        return res.data();
    }
}
