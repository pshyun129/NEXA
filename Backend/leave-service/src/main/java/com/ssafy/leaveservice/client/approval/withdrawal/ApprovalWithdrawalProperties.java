package com.ssafy.leaveservice.client.approval.withdrawal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("leave.approval.withdrawal")
public record ApprovalWithdrawalProperties(boolean enabled) {
}
