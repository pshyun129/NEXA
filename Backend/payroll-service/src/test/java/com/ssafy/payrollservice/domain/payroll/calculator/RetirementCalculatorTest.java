package com.ssafy.payrollservice.domain.payroll.calculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class RetirementCalculatorTest {

    @Test
    @DisplayName("근속 974일, 8~10월 임금 10,747,003원: 평균임금 116,815원, 퇴직금 9,351,600원")
    void retirementPay() {
        RetirementResult r = RetirementCalculator.calculate(
                LocalDate.of(2024, 3, 2), LocalDate.of(2026, 11, 1),
                10_747_003, 0, 0);

        assertEquals(974, r.serviceDays());
        assertEquals(LocalDate.of(2026, 8, 1), r.periodStart());
        assertEquals(92, r.periodDays());                   // 8월 31 + 9월 30 + 10월 31
        assertEquals(116_815, r.dailyAverageWage());        // 10,747,003 ÷ 92 = 116,815.25
        assertEquals(9_351_600, r.retirementPay());         // 116,815 × 30 × 974 ÷ 365
    }

    @Test
    @DisplayName("상여·연차수당은 3/12만 평균임금에 반영")
    void bonusAndLeavePay() {
        RetirementResult r = RetirementCalculator.calculate(
                LocalDate.of(2025, 11, 1), LocalDate.of(2026, 11, 1),
                9_000_000, 4_000_000, 1_200_000);

        // (9,000,000 + 4,000,000 × 3/12 + 1,200,000 × 3/12) ÷ 92 = 111,956.52
        assertEquals(111_956, r.dailyAverageWage());
        assertEquals(3_358_680, r.retirementPay());         // 근속 365일: 111,956 × 30
    }

    @Test
    @DisplayName("근속 1년 미만(364일)이면 퇴직금 0원")
    void underOneYear() {
        RetirementResult r = RetirementCalculator.calculate(
                LocalDate.of(2025, 11, 2), LocalDate.of(2026, 11, 1),
                9_000_000, 0, 0);

        assertEquals(364, r.serviceDays());
        assertEquals(0, r.retirementPay());
    }
}