package com.ssafy.attendanceservice.global.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 서비스 간 호출 설정. Eureka 서비스 이름(http://leave-service 등)으로 호출하고 로드밸런서가 실제 주소로 바꾼다.
 * 로컬에서도 Eureka와 호출할 서비스가 떠 있어야 한다 (결재 서비스와 같은 방식)
 */
@Configuration
public class RestClientConfig {

    // defaultCandidate = false: Eureka 클라이언트 등 다른 곳이 이 빌더를 주입받으면
    // Eureka 서버 주소(localhost)까지 서비스 이름으로 찾다가 등록에 실패한다. @LoadBalanced 로 요청한 곳만 받는다
    @Bean(defaultCandidate = false)
    @LoadBalanced
    RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }
}
