package com.ssafy.leaveservice.domain.grant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = ManualLeaveGrantRequestDeserializer.class)
public record ManualLeaveGrantRequest(
        @NotNull @Positive Long balanceTypeId,
        @NotNull GrantMode grantMode,
        @NotNull @Positive Integer grantMinutes,
        @NotNull OffsetDateTime validFrom,
        OffsetDateTime expiresOn,
        @NotBlank @Size(max = 500) String reason
) {
    public enum GrantMode { INITIAL, ADDITIONAL }
}
