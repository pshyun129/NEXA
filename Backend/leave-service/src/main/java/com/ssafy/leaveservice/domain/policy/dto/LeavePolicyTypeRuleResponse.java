package com.ssafy.leaveservice.domain.policy.dto;

import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyTypeRule;
import com.ssafy.leaveservice.domain.policy.entity.PolicyAllowedUnits;

public record LeavePolicyTypeRuleResponse(
        Long ruleId,
        Long policyVersionId,
        Long leaveTypeId,
        boolean isRequestable,
        PolicyAllowedUnits allowedUnits,
        Integer minMinutes,
        Integer maxMinutes
) {

    public static LeavePolicyTypeRuleResponse from(LeavePolicyTypeRule rule) {
        return new LeavePolicyTypeRuleResponse(
                rule.getRuleId(),
                rule.getPolicyVersionId(),
                rule.getLeaveTypeId(),
                rule.isRequestable(),
                rule.getAllowedUnits(),
                rule.getMinMinutes(),
                rule.getMaxMinutes()
        );
    }
}
