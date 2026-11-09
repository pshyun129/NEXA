package com.ssafy.payrollservice.domain.payroll.calculator;

/**
 * 지급합계로 공제 항목을 계산한다. DB/Spring에 의존하지 않는 순수 계산 로직.
 * 4대보험은 2026 근로자 부담 요율, 10원 미만 버림. 기준소득 상한·하한은 적용하지 않는다.
 */
public final class DeductionCalculator {

    private DeductionCalculator() {
    }

    /**
     * @param totalPay 지급합계 (비과세 항목 없음, 과세·부과 기준 동일)
     */
    public static DeductionResult calculate(int totalPay) {
        int nationalPension = floor10((long) totalPay * 475 / 10_000);          // 4.75%
        int healthInsurance = floor10((long) totalPay * 3_595 / 100_000);       // 3.595%
        int longTermCare = floor10((long) healthInsurance * 1_314 / 10_000);    // 건강보험료 × 13.14%
        int employmentInsurance = floor10((long) totalPay * 9 / 1_000);         // 0.9%

        int incomeTax = IncomeTaxCalculator.monthlyTax(totalPay, nationalPension);
        int localIncomeTax = floor10(incomeTax / 10);                           // 소득세 × 10%

        return new DeductionResult(incomeTax, localIncomeTax, nationalPension,
                healthInsurance, longTermCare, employmentInsurance);
    }

    /** 10원 미만 버림 */
    static int floor10(long amount) {
        return (int) (amount / 10 * 10);
    }
}