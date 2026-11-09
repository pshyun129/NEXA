package com.ssafy.leaveservice.global.config;

import com.ssafy.leaveservice.client.approval.ApprovalClient;
import com.ssafy.leaveservice.client.approval.ApprovalHttpClient;
import com.ssafy.leaveservice.client.LeaveRestClientFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;

import com.ssafy.leaveservice.client.approval.ApprovalProperties;
import com.ssafy.leaveservice.client.approval.UnavailableApprovalClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApprovalProperties.class)
public class LeaveApprovalConfiguration {
    @Bean
    @ConditionalOnMissingBean(ApprovalClient.class)
    public ApprovalClient approvalClient(
            ApprovalProperties properties,
            ObjectProvider<LeaveRestClientFactory> clientFactory,
            @Value("${leave.approval.http-contract-verified:false}") boolean httpContractVerified,
            @Value("${leave.approval.results.enabled:false}") boolean resultsEnabled,
            @Value("${leave.approval.results.contract-verified:false}") boolean resultsContractVerified,
            @Value("${spring.kafka.bootstrap-servers:}") String kafkaServers
    ) {
        if (!properties.enabled() || !httpContractVerified || !resultsEnabled || !resultsContractVerified
                || kafkaServers.isBlank()) {
            return new UnavailableApprovalClient();
        }

        // 연동 활성화 조건과 문서 생성 계약은 유지하고 주소 선택만 공통 구성에 위임한다.
        RestClient client = clientFactory.getObject().create(
                properties.baseUrl(), properties.serviceId(), properties.connectTimeout(), properties.readTimeout());
        return new ApprovalHttpClient(client);
    }
}
