package com.ssafy.payrollservice.domain.payroll.calculator;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 법정 퇴직금 계산. DB/Spring에 의존하지 않는 순수 계산 로직.
 * 1일 평균임금 = (3개월 임금 + 상여 × 3/12 + 연차수당 × 3/12) ÷ 3개월 총일수
 * 3개월 임금에는 상여·연차수당을 넣지 않는다 (호출하는 쪽에서 빼서 넘긴다, 이중 반영 방지)
 * 퇴직금 = 1일 평균임금 × 30 × 근속일수 ÷ 365 (근속 1년 미만은 0원)
 * 금액은 모두 원 미만 버림.
 */
public final class RetirementCalculator {

    private RetirementCalculator() {
    }

    /**
     * @param hireDate       입사일
     * @param resignDate     퇴직일 (마지막 근무일 다음 날)
     * @param threeMonthPay  퇴직일 이전 3개월 임금 총액
     * @param annualBonus    퇴직일 이전 1년 상여금 총액
     * @param annualLeavePay 연차수당
     */
    public static RetirementResult calculate(LocalDate hireDate, LocalDate resignDate,
                                             int threeMonthPay, int annualBonus, int annualLeavePay) {
        int serviceDays = (int) ChronoUnit.DAYS.between(hireDate, resignDate);

        LocalDate periodStart = resignDate.minusMonths(3);
        int periodDays = (int) ChronoUnit.DAYS.between(periodStart, resignDate);

        // 상여·연차수당의 3/12를 정수로 계산하려고 분자 전체에 12를 곱한다
        long wageSumTimes12 = threeMonthPay * 12L + annualBonus * 3L + annualLeavePay * 3L;
        int dailyAverageWage = (int) (wageSumTimes12 / (12L * periodDays));

        // 근속 1년 미만은 퇴직금 없음
        int retirementPay = isUnderOneYear(hireDate, resignDate)
                ? 0
                : (int) ((long) dailyAverageWage * 30 * serviceDays / 365);

        return new RetirementResult(serviceDays, periodStart, periodDays,
                threeMonthPay, annualBonus, annualLeavePay, dailyAverageWage, retirementPay);
    }

    /** 근속 1년 미만이면 퇴직금이 없다 (퇴직일이 입사 1주년 전) */
    public static boolean isUnderOneYear(LocalDate hireDate, LocalDate resignDate) {
        return resignDate.isBefore(hireDate.plusYears(1));
    }
}