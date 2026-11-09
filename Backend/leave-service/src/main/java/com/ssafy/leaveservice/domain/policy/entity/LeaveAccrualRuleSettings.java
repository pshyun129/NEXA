package com.ssafy.leaveservice.domain.policy.entity;

public record LeaveAccrualRuleSettings(
        int minServiceMonths,
        Integer maxServiceMonths,
        AccrualFrequency frequency,
        int grantMinutes,
        boolean active
) {
}
