package com.ssafy.payrollservice.domain.payroll.calculator;

/**
 * 근로소득 간이세액 계산식 (소득세법 시행령 별표2 산출 방식, 공제대상가족 본인 1인).
 * 연간 총급여 = 월급여 × 12 → 근로소득공제 → 인적·연금보험료·특별소득공제 → 기본세율
 * → 근로소득세액공제 → 결정세액 ÷ 12, 10원 미만 버림.
 * 간이세액표는 급여구간 중간값으로 계산하지만, 표를 쓰지 않으므로 실제 월급여로 계산한다.
 */
public final class IncomeTaxCalculator {

    private static final long PERSONAL_DEDUCTION = 1_500_000;  // 인적공제 (본인 1인)

    private IncomeTaxCalculator() {
    }

    /**
     * @param monthlyPay      월 지급합계
     * @param monthlyPension  월 국민연금 공제액 (연금보험료공제)
     */
    public static int monthlyTax(int monthlyPay, int monthlyPension) {
        long annualPay = monthlyPay * 12L;

        long earnedIncome = annualPay - earnedIncomeDeduction(annualPay);
        long taxBase = earnedIncome - PERSONAL_DEDUCTION
                - monthlyPension * 12L
                - specialDeduction(annualPay);
        if (taxBase <= 0) {
            return 0;
        }

        long computedTax = basicTax(taxBase);
        long decidedTax = computedTax - Math.min(taxCredit(computedTax), taxCreditLimit(annualPay));
        if (decidedTax <= 0) {
            return 0;
        }
        return DeductionCalculator.floor10(decidedTax / 12);
    }

    /** 근로소득공제 (한도 2,000만 원) */
    private static long earnedIncomeDeduction(long pay) {
        long deduction;
        if (pay <= 5_000_000) {
            deduction = pay * 70 / 100;
        } else if (pay <= 15_000_000) {
            deduction = 3_500_000 + (pay - 5_000_000) * 40 / 100;
        } else if (pay <= 45_000_000) {
            deduction = 7_500_000 + (pay - 15_000_000) * 15 / 100;
        } else if (pay <= 100_000_000) {
            deduction = 12_000_000 + (pay - 45_000_000) * 5 / 100;
        } else {
            deduction = 14_750_000 + (pay - 100_000_000) * 2 / 100;
        }
        return Math.min(deduction, 20_000_000);
    }

    /** 특별소득공제 등 (공제대상가족 1명). 1억 2천만 원 초과도 마지막 구간 식을 쓴다. */
    private static long specialDeduction(long pay) {
        if (pay <= 30_000_000) {
            return 3_100_000 + pay * 4 / 100;
        } else if (pay <= 45_000_000) {
            return 3_100_000 + pay * 4 / 100 - (pay - 30_000_000) * 5 / 100;
        } else if (pay <= 70_000_000) {
            return 3_100_000 + pay * 15 / 1_000;
        }
        return 3_100_000 + pay * 5 / 1_000;
    }

    /** 기본세율 (누진공제 방식) */
    private static long basicTax(long base) {
        if (base <= 14_000_000) {
            return base * 6 / 100;
        } else if (base <= 50_000_000) {
            return 840_000 + (base - 14_000_000) * 15 / 100;
        } else if (base <= 88_000_000) {
            return 6_240_000 + (base - 50_000_000) * 24 / 100;
        } else if (base <= 150_000_000) {
            return 15_360_000 + (base - 88_000_000) * 35 / 100;
        } else if (base <= 300_000_000) {
            return 37_060_000 + (base - 150_000_000) * 38 / 100;
        } else if (base <= 500_000_000) {
            return 94_060_000 + (base - 300_000_000) * 40 / 100;
        } else if (base <= 1_000_000_000) {
            return 174_060_000 + (base - 500_000_000) * 42 / 100;
        }
        return 384_060_000 + (base - 1_000_000_000) * 45 / 100;
    }

    /** 근로소득세액공제 */
    private static long taxCredit(long computedTax) {
        if (computedTax <= 1_300_000) {
            return computedTax * 55 / 100;
        }
        return 715_000 + (computedTax - 1_300_000) * 30 / 100;
    }

    /** 근로소득세액공제 한도 */
    private static long taxCreditLimit(long pay) {
        if (pay <= 33_000_000) {
            return 740_000;
        } else if (pay <= 70_000_000) {
            return Math.max(740_000 - (pay - 33_000_000) * 8 / 1_000, 660_000);
        } else if (pay <= 120_000_000) {
            return Math.max(660_000 - (pay - 70_000_000) / 2, 500_000);
        }
        return Math.max(500_000 - (pay - 120_000_000) / 2, 200_000);
    }
}