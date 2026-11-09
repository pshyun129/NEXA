package com.ssafy.payrollservice.domain.payroll.calculator;

/** 공제 항목 계산 결과 (단위: 원) */
public record DeductionResult(
        int incomeTax,              // 근로소득세
        int localIncomeTax,         // 근로지방소득세
        int nationalPension,        // 국민연금
        int healthInsurance,        // 건강보험
        int longTermCare,           // 장기요양보험
        int employmentInsurance     // 고용보험
) {
    /** 공제합계 */
    public int total() {
        return incomeTax + localIncomeTax + nationalPension
                + healthInsurance + longTermCare + employmentInsurance;
    }
}