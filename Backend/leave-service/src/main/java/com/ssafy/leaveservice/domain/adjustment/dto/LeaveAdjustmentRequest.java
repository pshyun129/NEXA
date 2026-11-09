package com.ssafy.leaveservice.domain.adjustment.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = LeaveAdjustmentRequestDeserializer.class)
public record LeaveAdjustmentRequest(
        @NotNull @Positive Long accountId,
        @NotNull @Positive Long grantId,
        @NotNull Integer quantityDelta,
        @NotBlank @Size(max = 500) String reason
) {
    @AssertTrue(message = "조정량은 0이 아니어야 합니다.")
    public boolean isQuantityNonzero() {
        return quantityDelta == null || quantityDelta != 0;
    }
}
