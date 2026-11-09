package com.ssafy.leaveservice.client.approval;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("leave.approval")
public record ApprovalProperties(
        boolean enabled,
        String baseUrl,
        String serviceId,
        Duration connectTimeout,
        Duration readTimeout
) {
}
