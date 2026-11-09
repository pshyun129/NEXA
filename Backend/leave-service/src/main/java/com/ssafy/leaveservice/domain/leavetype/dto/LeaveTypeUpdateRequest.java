package com.ssafy.leaveservice.domain.leavetype.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ssafy.leaveservice.domain.leavetype.entity.BalanceMode;
import com.ssafy.leaveservice.domain.leavetype.entity.RequestUnit;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.Set;

@JsonDeserialize(using = LeaveTypeUpdateRequestDeserializer.class)
public record LeaveTypeUpdateRequest(
        @Size(max = 100, message = "휴가 유형명은 100자 이하여야 합니다.") String name,
        Boolean isPaid,
        BalanceMode balanceMode,
        Long balanceTypeId,
        RequestUnit requestUnit,
        @Positive(message = "최대 시간은 양수여야 합니다.") Integer maxMinutes,
        Boolean isActive,
        @JsonIgnore Set<String> suppliedFields
) {
    public LeaveTypeUpdateRequest {
        suppliedFields = Set.copyOf(suppliedFields);
    }

    public boolean hasField(String fieldName) {
        return suppliedFields.contains(fieldName);
    }

    @AssertTrue(message = "수정할 항목을 하나 이상 지정해야 합니다.")
    @JsonIgnore
    public boolean isUpdatePresent() {
        return !suppliedFields.isEmpty();
    }

    @AssertTrue(message = "휴가 유형명은 공백일 수 없습니다.")
    @JsonIgnore
    public boolean isNameValid() {
        return name == null || !name.isBlank();
    }

    @AssertTrue(message = "name, isPaid, balanceMode, requestUnit, isActive에는 null을 지정할 수 없습니다.")
    @JsonIgnore
    public boolean isNonNullableFieldsValid() {
        return (!hasField("name") || name != null)
                && (!hasField("isPaid") || isPaid != null)
                && (!hasField("balanceMode") || balanceMode != null)
                && (!hasField("requestUnit") || requestUnit != null)
                && (!hasField("isActive") || isActive != null);
    }
}
