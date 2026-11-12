package com.ssafy.attendanceservice.domain.attendance.dto;

import com.ssafy.attendanceservice.domain.attendance.entity.WorkGroup;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 주 소정 근로시간: 근무일 수 × 일 소정, 주 소정 근로시간을 넘지 않음 (QA 2026-11-09 C16) */
class WeeklyAttendanceResponseTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 16);

    private static WorkGroup workGroup() {
        WorkGroup workGroup = mock(WorkGroup.class);
        when(workGroup.getName()).thenReturn("기본그룹");
        when(workGroup.getWeeklyStdHours()).thenReturn(40);
        when(workGroup.getWeeklyMaxHours()).thenReturn(52);
        when(workGroup.getDailyStdMinutes()).thenReturn(480);
        return workGroup;
    }

    /** holidays에 든 요일만 휴일인 한 주 */
    private static WeeklyAttendanceResponse week(List<DayOfWeek> holidays) {
        List<AttendanceDaySummaryResponse> days = IntStream.range(0, 7)
                .mapToObj(MONDAY::plusDays)
                .map(date -> AttendanceDaySummaryResponse.empty(date, holidays.contains(date.getDayOfWeek()), List.of()))
                .toList();
        return WeeklyAttendanceResponse.of(MONDAY, MONDAY.plusDays(6), workGroup(), days, MONDAY);
    }

    @Test
    void 평소_주는_40시간() {
        assertThat(week(List.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)).summary().stdMin()).isEqualTo(40 * 60);
    }

    @Test
    void 휴일대체로_근무일이_줄면_줄어듦() {
        // 수요일이 대체휴일 → 근무일 4일 → 32시간
        assertThat(week(List.of(DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)).summary().stdMin())
                .isEqualTo(32 * 60);
    }

    @Test
    void 근무일이_늘어도_주_소정_40시간을_넘지_않음() {
        // 토요일이 근무일로 바뀌어 6일이어도 40시간
        assertThat(week(List.of(DayOfWeek.SUNDAY)).summary().stdMin()).isEqualTo(40 * 60);
    }
}
