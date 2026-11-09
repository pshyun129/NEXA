package com.ssafy.payrollservice.domain.payroll.dto;

import com.ssafy.payrollservice.domain.payroll.entity.Payslip;

import java.time.LocalDate;

/** 월 급여 생성 결과 */
public record PayslipGenerateResponse(
        Long payslipId,         // 명세서 ID
        Long empId,             // 사원 ID
        int payYear,            // 급여 연도
        int payMonth,           // 급여 월
        LocalDate payDate,      // 지급일
        int totalPay,           // 지급합계
        int totalDeduction,     // 공제합계
        int netPay              // 실지급액
) {

    public static PayslipGenerateResponse from(Payslip payslip) {
        return new PayslipGenerateResponse(
                payslip.getPayslipId(),
                payslip.getEmpId(),
                payslip.getPayYear(),
                payslip.getPayMonth(),
                payslip.getPayDate(),
                payslip.getTotalPay(),
                payslip.getTotalDeduction(),
                payslip.getNetPay());
    }
}