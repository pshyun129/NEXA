package com.ssafy.leaveservice.domain.policy.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ssafy.leaveservice.domain.policy.entity.PolicyBaseType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record LeavePolicyVersionCreateRequest(
        @NotNull(message = "정책 기준은 필수입니다.") PolicyBaseType baseType,
        String fiscalStart,
        @NotNull(message = "시간 단위는 필수입니다.")
        @Positive(message = "시간 단위는 양수여야 합니다.") Integer unitMinutes,
        @NotNull(message = "이월 허용 여부는 필수입니다.") Boolean carryOverAllowed,
        @Positive(message = "이월 제한 시간은 양수여야 합니다.") Integer carryOverLimitMinutes,
        @Size(max = 50, message = "만료 규칙은 50자 이하여야 합니다.") String expiryRule,
        OffsetDateTime effectiveFrom,
        OffsetDateTime effectiveTo
) {

    public LeavePolicyVersionCreateRequest {
        expiryRule = expiryRule == null ? "END_OF_VALIDITY" : expiryRule;
    }

    @JsonIgnore
    @AssertTrue(message = "입사일 기준은 회계연도 시작일을 사용할 수 없고, 회계연도 기준은 매년 유효한 MM-DD 시작일이 필요합니다.")
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

        // 평년을 기준으로 검사해 매년 존재하지 않는 02-29도 거부한다.
        int month = Integer.parseInt(fiscalStart.substring(0, 2));
        int day = Integer.parseInt(fiscalStart.substring(3, 5));
        try {
            LocalDate.of(2001, month, day);
            return true;
        } catch (DateTimeException exception) {
            return false;
        }
    }

    @JsonIgnore
    @AssertTrue(message = "이월을 허용하지 않으면 이월 제한 시간은 지정할 수 없습니다.")
    public boolean isCarryOverSettingValid() {
        return !Boolean.FALSE.equals(carryOverAllowed) || carryOverLimitMinutes == null;
    }

    @JsonIgnore
    @AssertTrue(message = "만료 규칙은 공백일 수 없습니다.")
    public boolean isExpiryRuleValid() {
        return expiryRule == null || !expiryRule.isBlank();
    }

    @JsonIgnore
    @AssertTrue(message = "종료 시각은 시작 시각보다 늦어야 합니다.")
    public boolean isEffectivePeriodValid() {
        return effectiveFrom == null || effectiveTo == null || effectiveTo.isAfter(effectiveFrom);
    }
}
