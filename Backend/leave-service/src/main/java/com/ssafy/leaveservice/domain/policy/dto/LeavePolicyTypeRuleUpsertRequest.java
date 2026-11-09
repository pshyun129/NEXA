package com.ssafy.leaveservice.domain.policy.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ssafy.leaveservice.domain.policy.entity.PolicyAllowedUnits;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = LeavePolicyTypeRuleUpsertRequestDeserializer.class)
public record LeavePolicyTypeRuleUpsertRequest(
        @NotNull(message = "신청 허용 여부는 필수입니다.") Boolean isRequestable,
        @NotNull(message = "신청 허용 단위는 필수입니다.") PolicyAllowedUnits allowedUnits,
        @Positive(message = "최소 시간은 양수여야 합니다.") Integer minMinutes,
        @Positive(message = "최대 시간은 양수여야 합니다.") Integer maxMinutes
) {

    @JsonIgnore
    @AssertTrue(message = "최대 시간은 최소 시간 이상이어야 합니다.")
    public boolean isMinuteRangeValid() {
        return minMinutes == null || maxMinutes == null || minMinutes <= maxMinutes;
    }
}
