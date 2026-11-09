package com.ssafy.leaveservice.domain.policy.dto;

import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyGroup;

public record LeavePolicyGroupResponse(Long policyId, String name) {

    public static LeavePolicyGroupResponse from(LeavePolicyGroup policyGroup) {
        return new LeavePolicyGroupResponse(policyGroup.getPolicyId(), policyGroup.getName());
    }
}
