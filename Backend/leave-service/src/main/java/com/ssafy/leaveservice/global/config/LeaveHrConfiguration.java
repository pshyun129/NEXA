package com.ssafy.leaveservice.global.config;

import com.ssafy.leaveservice.client.hr.HrClient;
import com.ssafy.leaveservice.client.hr.HrIntegrationProperties;
import com.ssafy.leaveservice.client.hr.UnavailableHrClient;
import com.ssafy.leaveservice.client.hr.UserServiceHrClient;
import com.ssafy.leaveservice.client.LeaveRestClientFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HrIntegrationProperties.class)
public class LeaveHrConfiguration {
    @Bean
    @ConditionalOnMissingBean(HrClient.class)
    public HrClient hrClient(
            HrIntegrationProperties properties,
            ObjectProvider<LeaveRestClientFactory> clientFactory
    ) {
        if (!properties.enabled() || properties.employeeInfoUri() == null || properties.employeeInfoUri().isBlank()) {
            return new UnavailableHrClient();
        }

        // 직원 JWT가 전달되는 User Service API 경로를 검증한다.
        for (String endpoint : new String[] { properties.employeeInfoUri(), properties.departmentMembersUri(), properties.dutyInfoUri() }) {
            if (endpoint == null || endpoint.isBlank()) {
                continue;
            }
            if (!endpoint.startsWith("/") || endpoint.startsWith("//") || endpoint.contains("://")) {
                throw new IllegalArgumentException("HR endpoints must be relative API paths");
            }
        }
        RestClient client = clientFactory.getObject().create(
                properties.baseUrl(), properties.serviceId(), properties.connectTimeout(), properties.readTimeout());
        return new UserServiceHrClient(client, properties);
    }
}
