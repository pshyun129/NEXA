package com.ssafy.attendanceservice.domain.attendance.dto;

import jakarta.validation.constraints.NotBlank;

/** 근무상태 변경 요청 */
public record WorkStatusChangeRequest(
        @NotBlank String statusCode     // 변경할 상태 코드 (WORKING, OFF_WORKING, LEAVE)
) {
}