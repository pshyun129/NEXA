package com.ssafy.payrollservice.domain.payroll.dto;

import jakarta.validation.constraints.NotNull;

import java.time.YearMonth;

/** 월 급여 생성 요청. 사원 정보(이름·부서·직위·입사일·직급)는 user-service에서 조회해 명세서에 남긴다 */
public record PayslipGenerateRequest(
        @NotNull Long empId,            // 사원 ID
        @NotNull YearMonth month        // 급여 월 (YYYY-MM)
) {
}
