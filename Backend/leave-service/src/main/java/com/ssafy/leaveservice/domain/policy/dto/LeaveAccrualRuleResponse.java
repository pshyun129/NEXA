package com.ssafy.leaveservice.domain.policy.dto;

import com.ssafy.leaveservice.domain.policy.entity.AccrualFrequency;
import com.ssafy.leaveservice.domain.policy.entity.LeaveAccrualRule;

public record LeaveAccrualRuleResponse(
        Long ruleId,
        Long policyVersionId,
        int minServiceMonths,
        Integer maxServiceMonths,
        AccrualFrequency frequency,
        int grantMinutes,
        boolean isActive
) {

    public static LeaveAccrualRuleResponse from(LeaveAccrualRule rule, Long policyVersionId) {
        return new LeaveAccrualRuleResponse(
                rule.getRuleId(),
                policyVersionId,
                rule.getMinServiceMonths(),
                rule.getMaxServiceMonths(),
                rule.getFrequency(),
                rule.getGrantMinutes(),
                rule.isActive()
        );
    }
}
