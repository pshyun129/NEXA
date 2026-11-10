package com.ssafy.attendanceservice.domain.attendance.calculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkTimeCalculatorTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final int DAILY_STD = 480;   // 일 소정 8시간
    private static final int BREAK = 60;        // 근무그룹 휴게 60분

    private static OffsetDateTime at(int day, int hour, int minute) {
        return OffsetDateTime.of(2026, 10, day, hour, minute, 0, 0, KST);
    }

    private static WorkLogEntry work(OffsetDateTime t) {
        return new WorkLogEntry(t, true);
    }

    private static WorkLogEntry off(OffsetDateTime t) {
        return new WorkLogEntry(t, false);
    }

    @Test
    @DisplayName("09~18시 상태 변경 없음: 휴게 60분 차감, 소정 8시간")
    void fullDayWithoutStatusChange() {
        List<WorkLogEntry> logs = List.of(work(at(21, 9, 0)), off(at(21, 18, 0)));

        WorkTimeResult r = WorkTimeCalculator.calculate(logs, at(21, 18, 0), DAILY_STD, BREAK, false);

        assertEquals(480, r.totalMin());
        assertEquals(480, r.regularMin());
        assertEquals(60, r.breakMin());
        assertEquals(0, r.excludedMin());
        assertEquals(0, r.otExtendedMin());
        assertEquals(0, r.unapprovedOtMin());
    }

    @Test
    @DisplayName("점심에 업무종료 1시간: 이미 쉬었으므로 휴게 추가 차감 없음")
    void lunchMarkedAsOffWorking() {
        List<WorkLogEntry> logs = List.of(
                work(at(21, 9, 0)), off(at(21, 12, 0)), work(at(21, 13, 0)), off(at(21, 18, 0)));

        WorkTimeResult r = WorkTimeCalculator.calculate(logs, at(21, 18, 0), DAILY_STD, BREAK, false);

        assertEquals(480, r.totalMin());
        assertEquals(60, r.excludedMin());
        assertEquals(0, r.breakMin());
    }

    @Test
    @DisplayName("점심에 40분만 쉼: 부족한 20분만 휴게로 차감")
    void shortLunch() {
        List<WorkLogEntry> logs = List.of(
                work(at(21, 9, 0)), off(at(21, 12, 0)), work(at(21, 12, 40)), off(at(21, 18, 0)));

        WorkTimeResult r = WorkTimeCalculator.calculate(logs, at(21, 18, 0), DAILY_STD, BREAK, false);

        assertEquals(480, r.totalMin());
        assertEquals(40, r.excludedMin());
        assertEquals(20, r.breakMin());
    }

    @Test
    @DisplayName("2시간 근무: 4시간 미만이라 휴게 없음")
    void shortDay() {
        List<WorkLogEntry> logs = List.of(work(at(21, 9, 0)), off(at(21, 11, 0)));

        WorkTimeResult r = WorkTimeCalculator.calculate(logs, at(21, 11, 0), DAILY_STD, BREAK, false);

        assertEquals(120, r.totalMin());
        assertEquals(120, r.regularMin());
        assertEquals(0, r.breakMin());
    }

    @Test
    @DisplayName("14~24시 근무: 소정 8시간(야간 1시간) + 연장 1시간(전부 야간)")
    void nightWork() {
        List<WorkLogEntry> logs = List.of(work(at(21, 14, 0)), off(at(22, 0, 0)));

        WorkTimeResult r = WorkTimeCalculator.calculate(logs, at(22, 0, 0), DAILY_STD, BREAK, false);

        // 휴게 60분은 주간(14:00~14:59)에서 차감 → 15:00~23:00 소정, 23:00~24:00 연장
        assertEquals(540, r.totalMin());
        assertEquals(480, r.regularMin());
        assertEquals(60, r.nightMin());         // 22:00~23:00
        assertEquals(60, r.otExtendedMin());
        assertEquals(60, r.otNightMin());       // 23:00~24:00
        assertEquals(60, r.unapprovedOtMin());
    }

    @Test
    @DisplayName("휴일 10~15시 근무: 휴게 30분 차감, 전부 휴일근로")
    void holidayWork() {
        List<WorkLogEntry> logs = List.of(work(at(24, 10, 0)), off(at(24, 15, 0)));

        WorkTimeResult r = WorkTimeCalculator.calculate(logs, at(24, 15, 0), DAILY_STD, BREAK, true);

        assertEquals(270, r.totalMin());
        assertEquals(30, r.breakMin());
        assertEquals(0, r.regularMin());
        assertEquals(270, r.otHolidayMin());
        assertEquals(0, r.otExtendedMin());
        assertEquals(270, r.unapprovedOtMin());
    }

    @Test
    @DisplayName("퇴근취소 후 다시 근무: 취소~재개 사이는 미포함")
    void clockOutCanceled() {
        // 09:00 출근 → 17:00 퇴근(취소됨) → 17:30 업무 → 19:00 퇴근
        List<WorkLogEntry> logs = List.of(
                work(at(21, 9, 0)), off(at(21, 17, 0)), work(at(21, 17, 30)), off(at(21, 19, 0)));

        WorkTimeResult r = WorkTimeCalculator.calculate(logs, at(21, 19, 0), DAILY_STD, BREAK, false);

        assertEquals(30, r.excludedMin());
        assertEquals(30, r.breakMin());         // 필요 60 - 이미 쉰 30
        assertEquals(540, r.totalMin());
        assertEquals(480, r.regularMin());
        assertEquals(60, r.otExtendedMin());
    }

    @Test
    @DisplayName("퇴근 전: 계산하지 않음")
    void notClockedOut() {
        List<WorkLogEntry> logs = List.of(work(at(21, 9, 0)));

        WorkTimeResult r = WorkTimeCalculator.calculate(logs, null, DAILY_STD, BREAK, false);

        assertEquals(WorkTimeResult.empty(), r);
    }

    @Test
    @DisplayName("근무일 09~21시, 18~20시 초과근무 승인: 연장 180분 중 승인 120분, 미승인 60분")
    void approvedOvertimeOnWorkday() {
        List<WorkLogEntry> logs = List.of(work(at(21, 9, 0)), off(at(21, 21, 0)));
        List<ApprovedOvertime> approved = List.of(new ApprovedOvertime(at(21, 18, 0), at(21, 20, 0)));

        WorkTimeResult r = WorkTimeCalculator.calculate(logs, at(21, 21, 0), DAILY_STD, BREAK, false, approved);

        assertEquals(180, r.otExtendedMin());
        assertEquals(120, r.approvedOtMin());
        assertEquals(60, r.unapprovedOtMin());
    }

    @Test
    @DisplayName("휴일 10~15시, 10~12시 승인: 휴게 30분 뺀 휴일근로 270분 중 승인 90분")
    void approvedOvertimeOnHoliday() {
        List<WorkLogEntry> logs = List.of(work(at(24, 10, 0)), off(at(24, 15, 0)));
        List<ApprovedOvertime> approved = List.of(new ApprovedOvertime(at(24, 10, 0), at(24, 12, 0)));

        WorkTimeResult r = WorkTimeCalculator.calculate(logs, at(24, 15, 0), DAILY_STD, BREAK, true, approved);

        assertEquals(270, r.otHolidayMin());
        assertEquals(90, r.approvedOtMin());         // 휴게로 빠진 10:00~10:30 제외
        assertEquals(180, r.unapprovedOtMin());
    }

    @Test
    @DisplayName("승인 구간이 소정 근로 시간대뿐이면 승인 초과근로 없음")
    void approvedRangeInsideRegularHours() {
        List<WorkLogEntry> logs = List.of(work(at(21, 9, 0)), off(at(21, 21, 0)));
        List<ApprovedOvertime> approved = List.of(new ApprovedOvertime(at(21, 13, 0), at(21, 15, 0)));

        WorkTimeResult r = WorkTimeCalculator.calculate(logs, at(21, 21, 0), DAILY_STD, BREAK, false, approved);

        assertEquals(0, r.approvedOtMin());
        assertEquals(180, r.unapprovedOtMin());
    }

    @Test
    @DisplayName("미승인 구간: 09~21시, 19~20시만 승인 → 18~19시와 20~21시 (QA F24: 퇴근 직전 2시간 19~21시가 아님)")
    void unapprovedRangesSkipApprovedPart() {
        List<WorkLogEntry> logs = List.of(work(at(21, 9, 0)));
        List<ApprovedOvertime> approved = List.of(new ApprovedOvertime(at(21, 19, 0), at(21, 20, 0)));

        List<TimeRange> ranges = WorkTimeCalculator.unapprovedOtRanges(logs, at(21, 21, 0), DAILY_STD, BREAK, false, approved);
        WorkTimeResult r = WorkTimeCalculator.calculate(logs, at(21, 21, 0), DAILY_STD, BREAK, false, approved);

        assertEquals(List.of(new TimeRange(at(21, 18, 0), at(21, 19, 0)), new TimeRange(at(21, 20, 0), at(21, 21, 0))), ranges);
        // 구간 합 = 집계의 미승인 분
        assertEquals(r.unapprovedOtMin(), ranges.stream()
                .mapToLong(x -> java.time.Duration.between(x.startAt(), x.endAt()).toMinutes()).sum());
    }

    @Test
    @DisplayName("미승인 구간: 승인 없음 → 소정 다음부터 퇴근까지 한 구간, 미승인 없으면 빈 목록")
    void unapprovedRangesWholeOrNone() {
        List<WorkLogEntry> logs = List.of(work(at(21, 9, 0)));

        assertEquals(List.of(new TimeRange(at(21, 18, 0), at(21, 21, 0))),
                WorkTimeCalculator.unapprovedOtRanges(logs, at(21, 21, 0), DAILY_STD, BREAK, false, List.of()));
        assertEquals(List.of(),
                WorkTimeCalculator.unapprovedOtRanges(logs, at(21, 18, 0), DAILY_STD, BREAK, false, List.of()));
    }
}