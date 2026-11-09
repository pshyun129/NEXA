package com.ssafy.payrollservice.domain.payroll.dto;

import com.ssafy.payrollservice.domain.payroll.calculator.RetirementResult;

import java.time.LocalDate;

/** 예상 퇴직금 */
public record RetirementEstimateResponse(
        LocalDate hireDate,             // 입사일
        LocalDate resignDate,           // 퇴직일 (마지막 근무일 다음 날)
        LocalDate settlementStart,      // 정산기간 시작 (입사일)
        LocalDate settlementEnd,        // 정산기간 끝 (마지막 근무일)
        int serviceDays,                // 근속일수
        LocalDate periodStart,          // 평균임금 산정기간 시작
        LocalDate periodEnd,            // 평균임금 산정기간 끝
        int periodDays,                 // 이전 3개월 총일수
        int threeMonthPay,              // 3개월 급여 총액
        int basePayslipCount,           // 3개월 급여 계산에 쓴 명세서 수 (3 미만이면 월평균 × 3으로 환산)
        int annualBonus,                // 직전 1년 상여 총액
        int annualLeavePay,             // 연차수당
        int dailyAverageWage,           // 1일 평균임금
        int retirementPay               // 예상 퇴직금
) {

    public static RetirementEstimateResponse of(LocalDate hireDate, LocalDate resignDate,
                                                int basePayslipCount, RetirementResult r) {
        LocalDate lastWorkDate = resignDate.minusDays(1);
        return new RetirementEstimateResponse(
                hireDate,
                resignDate,
                hireDate,
                lastWorkDate,
                r.serviceDays(),
                r.periodStart(),
                lastWorkDate,
                r.periodDays(),
                r.threeMonthPay(),
                basePayslipCount,
                r.annualBonus(),
                r.annualLeavePay(),
                r.dailyAverageWage(),
                r.retirementPay());
    }
}