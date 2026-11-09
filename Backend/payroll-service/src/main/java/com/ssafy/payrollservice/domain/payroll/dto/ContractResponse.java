package com.ssafy.payrollservice.domain.payroll.dto;

import com.ssafy.payrollservice.domain.payroll.entity.PayGrade;

/** 내 급여 정보 (현재 직급 기준) */
public record ContractResponse(
        Long gradeId,           // 직급 ID
        int monthlyBasePay,     // 월 기본급
        int annualSalary        // 연봉 (월 기본급 × 12)
) {

    public static ContractResponse from(PayGrade grade) {
        return new ContractResponse(
                grade.getGradeId(),
                grade.getBaseAmount(),
                grade.getBaseAmount() * 12);
    }
}