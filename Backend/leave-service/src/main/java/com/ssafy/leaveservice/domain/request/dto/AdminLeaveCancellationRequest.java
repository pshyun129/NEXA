package com.ssafy.leaveservice.domain.request.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = AdminLeaveCancellationRequestDeserializer.class)
public record AdminLeaveCancellationRequest(@NotBlank @Size(max = 2000) String reason) {
}
