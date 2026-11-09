package com.ssafy.leaveservice.domain.leavetype.repository.projection;

import com.ssafy.leaveservice.domain.leavetype.entity.RequestUnit;
import com.ssafy.leaveservice.domain.policy.entity.PolicyAllowedUnits;

public record RequestableLeaveTypeSettings(
        Long leaveTypeId,
        String leaveTypeName,
        RequestUnit requestUnit,
        Integer masterMaxMinutes,
        PolicyAllowedUnits allowedUnits,
        Integer policyMinMinutes,
        Integer policyMaxMinutes
) {
}
