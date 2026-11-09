package com.ssafy.leaveservice.domain.policy.dto;

import com.ssafy.leaveservice.domain.policy.entity.PolicyBaseType;

import java.time.OffsetDateTime;

public record MyLeavePolicyResponse(
        Long assignmentId,
        Long policyId,
        String policyName,
        OffsetDateTime assignmentEffectiveFrom,
        OffsetDateTime assignmentEffectiveTo,
        Long policyVersionId,
        Integer versionNo,
        PolicyBaseType baseType,
        String fiscalStart,
        Integer unitMinutes,
        Boolean carryOverAllowed,
        Integer carryOverLimitMinutes,
        String expiryRule,
        OffsetDateTime effectiveFrom,
        OffsetDateTime effectiveTo
) {
}
