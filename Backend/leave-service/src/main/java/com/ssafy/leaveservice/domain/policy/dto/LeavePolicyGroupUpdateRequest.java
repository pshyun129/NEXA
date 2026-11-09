package com.ssafy.leaveservice.domain.policy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LeavePolicyGroupUpdateRequest(
        @NotBlank(message = "정책 그룹 이름은 필수입니다.")
        @Size(max = 100, message = "정책 그룹 이름은 100자 이하여야 합니다.")
        String name
) {
}
