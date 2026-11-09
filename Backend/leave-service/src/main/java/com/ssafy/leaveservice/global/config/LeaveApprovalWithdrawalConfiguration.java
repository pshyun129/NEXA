package com.ssafy.leaveservice.global.config;

import com.ssafy.leaveservice.client.approval.withdrawal.ApprovalWithdrawalClient;
import com.ssafy.leaveservice.client.approval.withdrawal.ApprovalWithdrawalProperties;
import com.ssafy.leaveservice.client.approval.withdrawal.UnavailableApprovalWithdrawalClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApprovalWithdrawalProperties.class)
public class LeaveApprovalWithdrawalConfiguration {
    @Bean
    @ConditionalOnMissingBean(ApprovalWithdrawalClient.class)
    public ApprovalWithdrawalClient unavailableApprovalWithdrawalClient() {
        // TODO 원본 결재 문서 철회의 최종성·DTO·멱등성·결과 조회·인증 계약 확정 후 HTTP 구현체를 추가한다.
        return new UnavailableApprovalWithdrawalClient();
    }
}
