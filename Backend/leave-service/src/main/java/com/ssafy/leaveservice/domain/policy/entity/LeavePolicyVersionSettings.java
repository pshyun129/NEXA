package com.ssafy.leaveservice.domain.policy.entity;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record LeavePolicyVersionSettings(
        @NotNull PolicyBaseType baseType,
        String fiscalStart,
        @NotNull @Positive Integer unitMinutes,
        @NotNull Boolean carryOverAllowed,
        @Positive Integer carryOverLimitMinutes,
        @NotBlank @Size(max = 50) String expiryRule,
        OffsetDateTime effectiveFrom,
        OffsetDateTime effectiveTo
) {

    @AssertTrue
    public boolean isFiscalStartValid() {
        if (baseType == null) {
            return true;
        }
        if (baseType == PolicyBaseType.HIRE_DATE) {
            return fiscalStart == null;
        }
        if (fiscalStart == null || !fiscalStart.matches("[0-9]{2}-[0-9]{2}")) {
            return false;
        }

        // 평년 기준으로 검사하여 매년 존재하지 않는 02-29를 거부한다.
        int month = Integer.parseInt(fiscalStart.substring(0, 2));
        int day = Integer.parseInt(fiscalStart.substring(3, 5));
        try {
            LocalDate.of(2001, month, day);
            return true;
        } catch (DateTimeException exception) {
            return false;
        }
    }

    @AssertTrue
    public boolean isCarryOverSettingValid() {
        return !Boolean.FALSE.equals(carryOverAllowed) || carryOverLimitMinutes == null;
    }

    @AssertTrue
    public boolean isEffectivePeriodValid() {
        return effectiveFrom == null || effectiveTo == null || effectiveTo.isAfter(effectiveFrom);
    }
}
