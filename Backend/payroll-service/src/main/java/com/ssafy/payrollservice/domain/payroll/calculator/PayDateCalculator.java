package com.ssafy.payrollservice.domain.payroll.calculator;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Set;

/** 급여 지급일: 매월 25일, 주말·공휴일이면 직전 영업일로 당긴다 */
public final class PayDateCalculator {

    public static final int PAY_DAY = 25;

    private PayDateCalculator() {
    }

    /**
     * @param holidays 그 달의 공휴일 (법정·회사 지정)
     */
    public static LocalDate payDateOf(YearMonth month, Set<LocalDate> holidays) {
        LocalDate date = month.atDay(PAY_DAY);
        while (isWeekend(date) || holidays.contains(date)) {
            date = date.minusDays(1);
        }
        return date;
    }

    private static boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }
}
