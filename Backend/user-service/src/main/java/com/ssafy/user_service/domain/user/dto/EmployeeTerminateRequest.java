package com.ssafy.user_service.domain.user.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** 퇴직 처리 요청 (관리자 기능) */
public record EmployeeTerminateRequest(
        @NotNull(message = "퇴직일을 입력해 주세요.") LocalDate resignDate,  // 퇴사일자. 기본 메시지("널이어서는 안됩니다")는 화면에 못 쓴다(QA C37)
        String resignReason             // 퇴사사유
) {
}
