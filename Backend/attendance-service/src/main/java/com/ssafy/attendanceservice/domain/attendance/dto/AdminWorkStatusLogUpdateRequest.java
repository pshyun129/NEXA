package com.ssafy.attendanceservice.domain.attendance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

/** 관리자 근무 상태 로그 수정 요청. 대상 직원과 근무일은 기존 로그를 따른다. */
public record AdminWorkStatusLogUpdateRequest(
        @NotBlank String statusCode,        // 변경할 상태 코드 (CLOCK_OUT_CANCEL 제외)
        @NotNull OffsetDateTime startAt,    // 변경할 시각
        @NotBlank String reason             // 수정 사유
) {
}
