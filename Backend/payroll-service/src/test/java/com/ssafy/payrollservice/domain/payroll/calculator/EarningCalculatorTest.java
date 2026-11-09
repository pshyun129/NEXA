package com.ssafy.payrollservice.domain.payroll.calculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EarningCalculatorTest {

    private static final int BASE = 3_500_000;  // 시급 16,746원

    @Test
    @DisplayName("초과근로 없음: 기본급만 지급")
    void baseOnly() {
        EarningResult r = EarningCalculator.calculate(BASE, 0, 0, 0);

        assertEquals(16_746, r.hourlyWage());
        assertEquals(BASE, r.basePay());
        assertEquals(0, r.otExtendedPay());
        assertEquals(0, r.otNightPay());
        assertEquals(0, r.otHolidayPay());
        assertEquals(BASE, r.total());
    }

    @Test
    @DisplayName("연장 10시간, 야간 2시간, 휴일 8시간")
    void overtime() {
        EarningResult r = EarningCalculator.calculate(BASE, 600, 120, 480);

        assertEquals(251_190, r.otExtendedPay());   // 10h × 16,746 × 1.5
        assertEquals(16_746, r.otNightPay());       // 2h × 16,746 × 0.5
        assertEquals(200_952, r.otHolidayPay());    // 8h × 16,746 × 1.5
        assertEquals(3_968_888, r.total());
    }

    @Test
    @DisplayName("무급휴가 8시간: 시급 × 8시간을 지급에서 차감")
    void unpaidLeave() {
        EarningResult r = EarningCalculator.calculate(BASE, 0, 0, 0, 480);

        assertEquals(-133_968, r.unpaidLeaveDeduction());   // 8h × 16,746
        assertEquals(BASE, r.basePay());
        assertEquals(3_366_032, r.total());
    }

    @Test
    @DisplayName("무급휴가가 없으면 차감 0")
    void noUnpaidLeave() {
        EarningResult r = EarningCalculator.calculate(BASE, 0, 0, 0);

        assertEquals(0, r.unpaidLeaveDeduction());
    }

    @Test
    @DisplayName("분기 마지막 달: 상여금 = 기본급 100%")
    void quarterlyBonus() {
        EarningResult r = EarningCalculator.calculate(BASE, 0, 0, 0, 0, true, false);

        assertEquals(BASE, r.bonus());
        assertEquals(0, r.holidayBonus());
        assertEquals(BASE * 2, r.total());
    }

    @Test
    @DisplayName("설날·추석이 있는 달: 명절수당 = 기본급 50%")
    void holidayBonus() {
        EarningResult r = EarningCalculator.calculate(BASE, 0, 0, 0, 0, false, true);

        assertEquals(1_750_000, r.holidayBonus());
        assertEquals(0, r.bonus());
    }

    @Test
    @DisplayName("상여·명절이 아닌 달은 0")
    void noBonus() {
        EarningResult r = EarningCalculator.calculate(BASE, 0, 0, 0, 0);

        assertEquals(0, r.bonus());
        assertEquals(0, r.holidayBonus());
    }

    @Test
    @DisplayName("원 미만 버림: 연장 1분 = 418.65원 → 418원")
    void truncate() {
        EarningResult r = EarningCalculator.calculate(BASE, 1, 0, 0);

        assertEquals(418, r.otExtendedPay());
    }
}