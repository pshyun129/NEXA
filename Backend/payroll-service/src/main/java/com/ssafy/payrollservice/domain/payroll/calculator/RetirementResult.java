package com.ssafy.payrollservice.domain.payroll.calculator;

import java.time.LocalDate;

/** 퇴직금 계산 결과 (금액 단위: 원) */
public record RetirementResult(
        int serviceDays,            // 근속일수
        LocalDate periodStart,      // 평균임금 산정기간 시작일 (퇴직일 3개월 전)
        int periodDays,             // 이전 3개월 총일수
        int threeMonthPay,          // 3개월 임금 총액
        int annualBonus,            // 직전 1년 상여금 총액
        int annualLeavePay,         // 연차수당
        int dailyAverageWage,       // 1일 평균임금
        int retirementPay           // 예상 퇴직금
) {
}