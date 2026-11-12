package com.ssafy.payrollservice.domain.payroll.calculator;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PayDateCalculatorTest {

    @Test
    void 평일이면_25일() {
        // 2026-11-25 수요일
        assertThat(PayDateCalculator.payDateOf(YearMonth.of(2026, 11), Set.of()))
                .isEqualTo(LocalDate.of(2026, 11, 25));
    }

    @Test
    void 일요일이면_직전_금요일() {
        // 2026-10-25 일요일 → 10-23 금요일 (QA C1)
        assertThat(PayDateCalculator.payDateOf(YearMonth.of(2026, 10), Set.of()))
                .isEqualTo(LocalDate.of(2026, 10, 23));
    }

    @Test
    void 공휴일이면_직전_영업일() {
        // 2026-12-25 금요일 성탄절 → 12-24 목요일
        assertThat(PayDateCalculator.payDateOf(YearMonth.of(2026, 12), Set.of(LocalDate.of(2026, 12, 25))))
                .isEqualTo(LocalDate.of(2026, 12, 24));
    }

    @Test
    void 공휴일_앞이_주말이면_더_당김() {
        // 2027-01-25 월요일을 공휴일로 두면 → 01-22 금요일
        assertThat(PayDateCalculator.payDateOf(YearMonth.of(2027, 1), Set.of(LocalDate.of(2027, 1, 25))))
                .isEqualTo(LocalDate.of(2027, 1, 22));
    }
}
