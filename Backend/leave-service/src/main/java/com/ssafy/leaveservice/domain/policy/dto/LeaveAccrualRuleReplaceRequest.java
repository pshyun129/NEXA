package com.ssafy.leaveservice.domain.policy.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ssafy.leaveservice.domain.policy.entity.AccrualFrequency;
import com.ssafy.leaveservice.domain.policy.entity.LeaveAccrualRuleSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

@JsonDeserialize(using = LeaveAccrualRuleReplaceRequestDeserializer.class)
public record LeaveAccrualRuleReplaceRequest(
        @NotNull(message = "규칙 목록은 필수입니다.")
        @Valid List<@NotNull(message = "규칙 항목은 null일 수 없습니다.") Rule> rules
) {

    public record Rule(
            Long ruleId,
            @NotNull(message = "최소 근속 개월은 필수입니다.")
            @PositiveOrZero(message = "최소 근속 개월은 0 이상이어야 합니다.") Integer minServiceMonths,
            Integer maxServiceMonths,
            @NotNull(message = "발생 주기는 필수입니다.") AccrualFrequency frequency,
            @NotNull(message = "발생 시간은 필수입니다.")
            @Positive(message = "발생 시간은 양수여야 합니다.") Integer grantMinutes,
            @NotNull(message = "규칙 활성 여부는 필수입니다.") Boolean isActive
    ) {

        @JsonIgnore
        @AssertTrue(message = "최대 근속 개월은 최소 근속 개월 이상이어야 합니다.")
        public boolean isServiceRangeValid() {
            return minServiceMonths == null || maxServiceMonths == null || maxServiceMonths >= minServiceMonths;
        }

        public LeaveAccrualRuleSettings toSettings() {
            return new LeaveAccrualRuleSettings(minServiceMonths, maxServiceMonths, frequency, grantMinutes, isActive);
        }
    }
}
