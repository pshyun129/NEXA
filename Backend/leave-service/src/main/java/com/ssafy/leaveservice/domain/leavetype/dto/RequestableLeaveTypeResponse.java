package com.ssafy.leaveservice.domain.leavetype.dto;

import com.ssafy.leaveservice.domain.policy.entity.PolicyAllowedUnits;

public record RequestableLeaveTypeResponse(
        Long leaveTypeId,
        String leaveTypeName,
        PolicyAllowedUnits allowedUnits,
        Integer effectiveMinMinutes,
        Integer effectiveMaxMinutes
) {
}
