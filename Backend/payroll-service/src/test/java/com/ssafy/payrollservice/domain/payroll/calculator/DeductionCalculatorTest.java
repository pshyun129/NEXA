package com.ssafy.payrollservice.domain.payroll.calculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeductionCalculatorTest {

    @Test
    @DisplayName("지급합계 350만 원: 4대보험, 10원 미만 버림")
    void insurance() {
        DeductionResult r = DeductionCalculator.calculate(3_500_000);

        assertEquals(166_250, r.nationalPension());     // × 4.75%
        assertEquals(125_820, r.healthInsurance());     // 125,825 → 125,820
        assertEquals(16_530, r.longTermCare());         // 125,820 × 13.14% = 16,532.7 → 16,530
        assertEquals(31_500, r.employmentInsurance());  // × 0.9%
    }

    @Test
    @DisplayName("지급합계 350만 원: 근로소득세와 지방소득세")
    void incomeTax() {
        DeductionResult r = DeductionCalculator.calculate(3_500_000);

        assertEquals(124_020, r.incomeTax());           // 결정세액 1,488,250 ÷ 12
        assertEquals(12_400, r.localIncomeTax());       // 12,402 → 12,400
        assertEquals(476_520, r.total());
    }

    @Test
    @DisplayName("과세표준이 0 이하면 소득세 0원")
    void lowPayNoTax() {
        DeductionResult r = DeductionCalculator.calculate(500_000);

        assertEquals(0, r.incomeTax());
        assertEquals(0, r.localIncomeTax());
    }
}