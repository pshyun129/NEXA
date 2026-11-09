package com.ssafy.approval_service.domain.approval.policy;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ApprovalPolicyProperties.class)
class ApprovalPolicyConfig {
}
