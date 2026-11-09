package com.ssafy.leaveservice.domain.policy.dto;

import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyVersion;
import com.ssafy.leaveservice.domain.policy.entity.PolicyBaseType;

import java.time.OffsetDateTime;

public record LeavePolicyVersionListResponse(
        Long policyVersionId,
        int versionNo,
        PolicyBaseType baseType,
        OffsetDateTime effectiveFrom,
        OffsetDateTime effectiveTo,
        boolean isActive
) {
    public static LeavePolicyVersionListResponse from(LeavePolicyVersion policyVersion, boolean isActive) {
        return new LeavePolicyVersionListResponse(
                policyVersion.getPolicyVersionId(),
                policyVersion.getVersionNo(),
                policyVersion.getBaseType(),
                policyVersion.getEffectiveFrom(),
                policyVersion.getEffectiveTo(),
                isActive
        );
    }
}
