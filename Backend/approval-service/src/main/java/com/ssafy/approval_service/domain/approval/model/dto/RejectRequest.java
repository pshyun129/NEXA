package com.ssafy.approval_service.domain.approval.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 결재 반려 요청. 반려 사유는 필수 */
public record RejectRequest(
        @NotBlank @Size(max = 1000) String comment   // 반려 사유
) {
}
