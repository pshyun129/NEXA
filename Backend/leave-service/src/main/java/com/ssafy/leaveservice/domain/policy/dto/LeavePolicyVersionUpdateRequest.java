package com.ssafy.leaveservice.domain.policy.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ssafy.leaveservice.domain.policy.entity.PolicyBaseType;
import jakarta.validation.constraints.AssertTrue;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.OffsetDateTime;
import java.util.Set;

@JsonDeserialize(using = LeavePolicyVersionUpdateRequestDeserializer.class)
public record LeavePolicyVersionUpdateRequest(
        PolicyBaseType baseType,
        String fiscalStart,
        Integer unitMinutes,
        Boolean carryOverAllowed,
        Integer carryOverLimitMinutes,
        String expiryRule,
        OffsetDateTime effectiveFrom,
        OffsetDateTime effectiveTo,
        @JsonIgnore Set<String> suppliedFields
) {

    public LeavePolicyVersionUpdateRequest {
        suppliedFields = Set.copyOf(suppliedFields);
    }

    public boolean hasField(String fieldName) {
        return suppliedFields.contains(fieldName);
    }

    @JsonIgnore
    @AssertTrue(message = "수정할 항목을 하나 이상 지정해야 합니다.")
    public boolean isUpdatePresent() {
        return !suppliedFields.isEmpty();
    }

    @JsonIgnore
    @AssertTrue(message = "baseType, unitMinutes, carryOverAllowed, expiryRule에는 null을 지정할 수 없습니다.")
    public boolean isNonNullableFieldsValid() {
        return (!hasField("baseType") || baseType != null)
                && (!hasField("unitMinutes") || unitMinutes != null)
                && (!hasField("carryOverAllowed") || carryOverAllowed != null)
                && (!hasField("expiryRule") || expiryRule != null);
    }
}
