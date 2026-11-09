package com.ssafy.attendanceservice.domain.attendance.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** 근무일 마감: 출근한 날 다음날 06:00 (KST) */
class AttendanceDailyTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 11, 6);

    private static OffsetDateTime at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atOffset(KST);
    }

    private static AttendanceDaily clockedIn(LocalDate date) {
        return AttendanceDaily.builder()
                .empId(1L)
                .workDate(date)
                .clockIn(at(date, 8, 46))
                .isHoliday(false)
                .build();
    }

    @Test
    void 자정을_넘긴_야근은_다음날_06시_전까지_마감_전() {
        AttendanceDaily daily = clockedIn(FRIDAY);

        assertThat(daily.isPastWorkDayCutoff(at(FRIDAY.plusDays(1), 5, 59))).isFalse();
        assertThat(daily.isUnclosedPastCutoff(at(FRIDAY.plusDays(1), 5, 59))).isFalse();
    }

    @Test
    void 다음날_06시부터_마감() {
        AttendanceDaily daily = clockedIn(FRIDAY);

        assertThat(daily.isPastWorkDayCutoff(at(FRIDAY.plusDays(1), 6, 0))).isTrue();
        assertThat(daily.isUnclosedPastCutoff(at(FRIDAY.plusDays(3), 9, 23))).isTrue();
    }

    @Test
    void 퇴근한_근무는_마감이_지나도_퇴근_누락이_아님() {
        AttendanceDaily daily = clockedIn(FRIDAY);
        daily.clockOut(at(FRIDAY, 18, 0));

        assertThat(daily.isUnclosedPastCutoff(at(FRIDAY.plusDays(3), 9, 0))).isFalse();
    }

    @Test
    void 다른_시간대로_들어와도_KST_기준으로_판단() {
        AttendanceDaily daily = clockedIn(FRIDAY);

        // 2026-11-06T20:59Z = 11/7 05:59 KST (마감 전), 21:00Z = 06:00 KST (마감)
        assertThat(daily.isPastWorkDayCutoff(OffsetDateTime.of(2026, 11, 6, 20, 59, 0, 0, ZoneOffset.UTC))).isFalse();
        assertThat(daily.isPastWorkDayCutoff(OffsetDateTime.of(2026, 11, 6, 21, 0, 0, 0, ZoneOffset.UTC))).isTrue();
    }
}
