package com.ssafy.attendanceservice.domain.attendance.calculator;

import com.ssafy.attendanceservice.domain.attendance.entity.enums.AnomalyType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnomalyJudgeTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final LocalDate WORK_DATE = LocalDate.of(2026, 10, 21);
    private static final LocalTime START = LocalTime.of(9, 0);
    private static final LocalTime END = LocalTime.of(18, 0);
    private static final int DAILY_STD = 480;

    private static OffsetDateTime at(int day, int hour, int minute, int second) {
        return OffsetDateTime.of(2026, 10, day, hour, minute, second, 0, KST);
    }

    private static WorkTimeResult workTime(int totalMin, int unapprovedOtMin) {
        return new WorkTimeResult(totalMin, 0, 0, 0, 0, 0, 0, 0, 0, unapprovedOtMin);
    }

    @Test
    @DisplayName("09:01 출근: 지각")
    void late() {
        assertEquals(List.of(AnomalyType.LATE),
                AnomalyJudge.onClockIn(WORK_DATE, at(21, 9, 1, 0), START, false));
    }

    @Test
    @DisplayName("09:00:59 출근: 초는 버려서 지각 아님")
    void notLateWithinMinute() {
        assertTrue(AnomalyJudge.onClockIn(WORK_DATE, at(21, 9, 0, 59), START, false).isEmpty());
    }

    @Test
    @DisplayName("휴일에는 늦게 출근해도 지각 아님")
    void notLateOnHoliday() {
        assertTrue(AnomalyJudge.onClockIn(WORK_DATE, at(21, 13, 0, 0), START, true).isEmpty());
    }

    @Test
    @DisplayName("17:00 퇴근, 7시간 근무: 조퇴 + 소정근로 미달")
    void earlyLeaveAndUnderStd() {
        assertEquals(List.of(AnomalyType.EARLY_LEAVE, AnomalyType.UNDER_STD_HOURS),
                AnomalyJudge.onClockOut(WORK_DATE, at(21, 17, 0, 0), END, DAILY_STD, false, workTime(420, 0)));
    }

    @Test
    @DisplayName("19:00 퇴근, 9시간 근무: 미승인 초과근로")
    void unapprovedOvertime() {
        assertEquals(List.of(AnomalyType.UNAPPROVED_OT),
                AnomalyJudge.onClockOut(WORK_DATE, at(21, 19, 0, 0), END, DAILY_STD, false, workTime(540, 60)));
    }

    @Test
    @DisplayName("18:00 퇴근, 8시간 근무: 이상 없음")
    void normal() {
        assertTrue(AnomalyJudge.onClockOut(WORK_DATE, at(21, 18, 0, 0), END, DAILY_STD, false, workTime(480, 0)).isEmpty());
    }

    @Test
    @DisplayName("다음 날 02:00 퇴근: 날짜까지 비교해서 조퇴 아님")
    void notEarlyLeaveAfterMidnight() {
        List<AnomalyType> result = AnomalyJudge.onClockOut(
                WORK_DATE, at(22, 2, 0, 0), END, DAILY_STD, false, workTime(480, 0));
        assertFalse(result.contains(AnomalyType.EARLY_LEAVE));
    }

    @Test
    @DisplayName("휴일 근무: 조퇴/미달 없이 미승인 초과근로만")
    void holidayOnlyUnapprovedOt() {
        assertEquals(List.of(AnomalyType.UNAPPROVED_OT),
                AnomalyJudge.onClockOut(WORK_DATE, at(21, 12, 0, 0), END, DAILY_STD, true, workTime(180, 180)));
    }
}