package com.ssafy.leaveservice.domain.policy.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = LeavePolicyActivationRequestDeserializer.class)
public record LeavePolicyActivationRequest(@NotNull @Positive Long versionId) {
}
