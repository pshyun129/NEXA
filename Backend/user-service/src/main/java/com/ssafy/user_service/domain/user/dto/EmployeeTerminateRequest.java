package com.ssafy.user_service.domain.user.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** 퇴직 처리 요청 (관리자 기능) */
public record EmployeeTerminateRequest(
        @NotNull LocalDate resignDate,  // 퇴사일자
        String resignReason             // 퇴사사유
) {
}
