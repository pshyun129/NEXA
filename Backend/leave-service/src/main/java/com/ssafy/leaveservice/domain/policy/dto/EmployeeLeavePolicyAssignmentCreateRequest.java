package com.ssafy.leaveservice.domain.policy.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.OffsetDateTime;

@JsonDeserialize(using = EmployeeLeavePolicyAssignmentCreateRequestDeserializer.class)
public record EmployeeLeavePolicyAssignmentCreateRequest(
        @NotNull @Positive Long policyId,
        OffsetDateTime effectiveFrom,
        OffsetDateTime effectiveTo
) {

    @JsonIgnore
    @AssertTrue(message = "종료 시각은 시작 시각보다 늦어야 합니다.")
    public boolean isPeriodValid() {
        return effectiveFrom == null || effectiveTo == null || effectiveTo.isAfter(effectiveFrom);
    }
}
