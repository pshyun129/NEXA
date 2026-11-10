package com.ssafy.attendanceservice.domain.attendance.calculator;

import com.ssafy.attendanceservice.global.util.DateTimeUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 상태 로그로 하루 근로시간을 계산한다. DB/Spring에 의존하지 않는 순수 계산 로직.
 * 1분 단위로 근무 구간을 펼쳐서 휴게 차감 → 소정/초과 → 야간/휴일 순서로 분류한다.
 */
public final class WorkTimeCalculator {

    private static final int HALF_BREAK_THRESHOLD = 240;   // 4시간 이상 근무 시 휴게 30분
    private static final int HALF_BREAK_MINUTES = 30;
    private static final int FULL_BREAK_THRESHOLD = 480;   // 8시간 이상 근무 시 근무그룹 휴게시간

    private static final int NIGHT_START_HOUR = 22;        // 야간 22:00 ~ 06:00
    private static final int NIGHT_END_HOUR = 6;

    private WorkTimeCalculator() {
    }

    /**
     * 승인된 초과근무 없이 계산 (초과근로는 전부 미승인)
     * @param logs            그 근무일의 상태 로그 (출근 ~ 퇴근)
     * @param clockOut        퇴근 시각 (null이면 계산하지 않음)
     * @param dailyStdMinutes 일 소정근로시간(분)
     * @param breakMinutes    근무그룹 휴게시간(분)
     * @param isHoliday       휴일 여부
     */
    public static WorkTimeResult calculate(List<WorkLogEntry> logs, OffsetDateTime clockOut,
                                           int dailyStdMinutes, int breakMinutes, boolean isHoliday) {
        return calculate(logs, clockOut, dailyStdMinutes, breakMinutes, isHoliday, List.of());
    }

    /**
     * @param approvedOvertimes 그날 승인된 초과근무 구간. 이 안의 초과근로만 승인, 나머지는 미승인
     */
    public static WorkTimeResult calculate(List<WorkLogEntry> logs, OffsetDateTime clockOut,
                                           int dailyStdMinutes, int breakMinutes, boolean isHoliday,
                                           List<ApprovedOvertime> approvedOvertimes) {
        if (logs.isEmpty() || clockOut == null) {
            return WorkTimeResult.empty();
        }

        // 1·2. 근무 구간을 1분 단위로 펼치고 휴게를 뺀다
        CountedMinutes cm = countMinutes(logs, clockOut, breakMinutes);
        List<OffsetDateTime> counted = cm.counted();
        int excludedMin = cm.excludedMin();
        int breakMin = cm.breakMin();
        int totalMin = counted.size();

        // 3. 소정/초과/야간/휴일 분류. 초과근로는 승인 구간 안이면 승인으로 센다
        int regularMin = 0, nightMin = 0, otExtendedMin = 0, otNightMin = 0, otHolidayMin = 0, approvedOtMin = 0;

        if (isHoliday) {
            // 휴일: 전부 휴일근로
            otHolidayMin = totalMin;
            for (OffsetDateTime t : counted) {
                if (isNight(t)) otNightMin++;
                if (isApproved(t, approvedOvertimes)) approvedOtMin++;
            }
        } else {
            // 근무일: 시간 순서로 앞의 소정근로시간까지 소정, 나머지 연장
            for (int i = 0; i < counted.size(); i++) {
                OffsetDateTime t = counted.get(i);
                boolean night = isNight(t);
                if (i < dailyStdMinutes) {
                    regularMin++;
                    if (night) nightMin++;
                } else {
                    otExtendedMin++;
                    if (night) otNightMin++;
                    if (isApproved(t, approvedOvertimes)) approvedOtMin++;
                }
            }
        }

        // 4. 초과근로 중 승인 구간 밖은 미승인
        int unapprovedOtMin = (isHoliday ? otHolidayMin : otExtendedMin) - approvedOtMin;

        return new WorkTimeResult(totalMin, regularMin, breakMin, excludedMin,
                nightMin, otExtendedMin, otNightMin, otHolidayMin, approvedOtMin, unapprovedOtMin);
    }

    /**
     * 미승인 초과근로가 실제로 있는 시간 구간 [시작, 끝). calculate()와 같은 기준(휴게 차감, 소정 다음부터 초과)으로 센다.
     * 초과근무 신청을 미리 채울 때 쓴다 (퇴근 직전으로만 계산하면 이미 승인된 구간과 겹친다)
     */
    public static List<TimeRange> unapprovedOtRanges(List<WorkLogEntry> logs, OffsetDateTime clockOut,
                                                     int dailyStdMinutes, int breakMinutes, boolean isHoliday,
                                                     List<ApprovedOvertime> approvedOvertimes) {
        if (logs.isEmpty() || clockOut == null) {
            return List.of();
        }
        List<OffsetDateTime> counted = countMinutes(logs, clockOut, breakMinutes).counted();

        List<TimeRange> ranges = new ArrayList<>();
        OffsetDateTime rangeStart = null;
        OffsetDateTime prev = null;
        for (int i = 0; i < counted.size(); i++) {
            OffsetDateTime t = counted.get(i);
            boolean overtime = isHoliday || i >= dailyStdMinutes;
            boolean unapproved = overtime && !isApproved(t, approvedOvertimes);
            boolean continues = prev != null && t.equals(prev.plusMinutes(1));
            if (rangeStart != null && (!unapproved || !continues)) {
                ranges.add(new TimeRange(rangeStart, prev.plusMinutes(1)));
                rangeStart = null;
            }
            if (unapproved && rangeStart == null) {
                rangeStart = t;
            }
            prev = t;
        }
        if (rangeStart != null) {
            ranges.add(new TimeRange(rangeStart, prev.plusMinutes(1)));
        }
        return ranges;
    }

    /** 1분 단위로 펼친 근로 시각(휴게 차감 후)과 미포함·휴게 분 */
    private record CountedMinutes(List<OffsetDateTime> counted, int excludedMin, int breakMin) {
    }

    private static CountedMinutes countMinutes(List<WorkLogEntry> logs, OffsetDateTime clockOut, int breakMinutes) {
        // 1. 근무 구간을 1분 단위로 펼치고, 미포함 시간은 따로 센다
        List<OffsetDateTime> workMinutes = new ArrayList<>();
        int excludedMin = 0;

        List<WorkLogEntry> sorted = logs.stream()
                .sorted(Comparator.comparing(WorkLogEntry::startAt))
                .toList();
        OffsetDateTime end = toMinute(clockOut);

        for (int i = 0; i < sorted.size(); i++) {
            OffsetDateTime from = toMinute(sorted.get(i).startAt());
            OffsetDateTime to = (i + 1 < sorted.size()) ? toMinute(sorted.get(i + 1).startAt()) : end;
            if (to.isAfter(end)) {
                to = end;
            }
            for (OffsetDateTime t = from; t.isBefore(to); t = t.plusMinutes(1)) {
                if (sorted.get(i).countAsWork()) {
                    workMinutes.add(t);
                } else {
                    excludedMin++;
                }
            }
        }

        // 2. 휴게 차감: 필요한 휴게 - 이미 쉰 시간(미포함)
        int requiredBreak = requiredBreak(workMinutes.size(), breakMinutes);
        int breakMin = Math.max(0, requiredBreak - excludedMin);
        return new CountedMinutes(removeBreak(workMinutes, breakMin), excludedMin, breakMin);
    }

    /** 근무 시간에 따라 필요한 휴게시간 (근로기준법: 4시간 30분, 8시간 1시간) */
    private static int requiredBreak(int workMinutes, int breakMinutes) {
        if (workMinutes >= FULL_BREAK_THRESHOLD) {
            return breakMinutes;
        }
        if (workMinutes >= HALF_BREAK_THRESHOLD) {
            return HALF_BREAK_MINUTES;
        }
        return 0;
    }

    /** 휴게는 점심으로 보고 주간 시간에서 앞쪽부터 뺀다. 주간이 부족하면 남은 만큼 앞에서 뺀다. */
    private static List<OffsetDateTime> removeBreak(List<OffsetDateTime> minutes, int breakMin) {
        List<OffsetDateTime> result = new ArrayList<>();
        int remaining = breakMin;
        for (OffsetDateTime t : minutes) {
            if (remaining > 0 && !isNight(t)) {
                remaining--;
                continue;
            }
            result.add(t);
        }
        if (remaining > 0) {
            result = new ArrayList<>(result.subList(Math.min(remaining, result.size()), result.size()));
        }
        return result;
    }

    private static boolean isApproved(OffsetDateTime minute, List<ApprovedOvertime> approvedOvertimes) {
        return approvedOvertimes.stream().anyMatch(range -> range.contains(minute));
    }

    private static boolean isNight(OffsetDateTime t) {
        int hour = t.atZoneSameInstant(DateTimeUtils.ZONE).getHour();
        return hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR;
    }

    private static OffsetDateTime toMinute(OffsetDateTime t) {
        return t.truncatedTo(ChronoUnit.MINUTES);
    }
}