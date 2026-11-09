package com.ssafy.attendanceservice.domain.attendance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 관리자 근무 상태 로그 등록 요청 */
public record AdminWorkStatusLogRequest(
        @NotNull Long empId,                // 대상 사원 ID
        @NotNull LocalDate workDate,        // 근무일
        @NotBlank String statusCode,        // 상태 코드 (CLOCK_OUT_CANCEL 제외)
        @NotNull OffsetDateTime startAt,    // 상태 시작 시각
        @NotBlank String reason             // 보정 사유
) {
}