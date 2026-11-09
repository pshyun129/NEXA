package com.ssafy.leaveservice.domain.policy.dto;

import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyVersion;
import com.ssafy.leaveservice.domain.policy.entity.PolicyBaseType;

import java.time.OffsetDateTime;

public record LeavePolicyVersionDetailResponse(
        Long policyVersionId,
        Long policyId,
        int versionNo,
        PolicyBaseType baseType,
        String fiscalStart,
        int unitMinutes,
        boolean carryOverAllowed,
        Integer carryOverLimitMinutes,
        String expiryRule,
        OffsetDateTime effectiveFrom,
        OffsetDateTime effectiveTo,
        OffsetDateTime createdAt,
        boolean isActive
) {

    public static LeavePolicyVersionDetailResponse from(
            LeavePolicyVersion policyVersion,
            Long policyId,
            boolean isActive
    ) {
        return new LeavePolicyVersionDetailResponse(
                policyVersion.getPolicyVersionId(),
                policyId,
                policyVersion.getVersionNo(),
                policyVersion.getBaseType(),
                policyVersion.getFiscalStart(),
                policyVersion.getUnitMinutes(),
                policyVersion.isCarryOverAllowed(),
                policyVersion.getCarryOverLimitMinutes(),
                policyVersion.getExpiryRule(),
                policyVersion.getEffectiveFrom(),
                policyVersion.getEffectiveTo(),
                policyVersion.getCreatedAt(),
                isActive
        );
    }
}
