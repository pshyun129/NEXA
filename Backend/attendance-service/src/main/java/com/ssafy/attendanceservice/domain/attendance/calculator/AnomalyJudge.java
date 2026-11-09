package com.ssafy.attendanceservice.domain.attendance.calculator;

import com.ssafy.attendanceservice.domain.attendance.entity.enums.AnomalyType;
import com.ssafy.attendanceservice.global.util.DateTimeUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** 근태이상 판정. DB/Spring에 의존하지 않는 순수 판정 로직. 휴일은 지각/조퇴/미달을 판정하지 않는다. */
public final class AnomalyJudge {

    private AnomalyJudge() {
    }

    /** 출근 시: 지각 */
    public static List<AnomalyType> onClockIn(LocalDate workDate, OffsetDateTime clockIn,
                                              LocalTime startTime, boolean isHoliday) {
        List<AnomalyType> result = new ArrayList<>();
        if (!isHoliday && startTime != null
                && toLocal(clockIn).isAfter(workDate.atTime(startTime))) {
            result.add(AnomalyType.LATE);
        }
        return result;
    }

    /** 퇴근 시: 조퇴, 소정근로 미달, 미승인 초과근로 */
    public static List<AnomalyType> onClockOut(LocalDate workDate, OffsetDateTime clockOut,
                                               LocalTime endTime, int dailyStdMinutes,
                                               boolean isHoliday, WorkTimeResult workTime) {
        List<AnomalyType> result = new ArrayList<>();
        if (!isHoliday) {
            if (endTime != null && toLocal(clockOut).isBefore(workDate.atTime(endTime))) {
                result.add(AnomalyType.EARLY_LEAVE);
            }
            if (workTime.totalMin() < dailyStdMinutes) {
                result.add(AnomalyType.UNDER_STD_HOURS);
            }
        }
        if (workTime.unapprovedOtMin() > 0) {
            result.add(AnomalyType.UNAPPROVED_OT);
        }
        return result;
    }

    /** 퇴근 시 판정하는 유형 (퇴근취소/재퇴근 때 지우고 다시 판정) */
    public static List<AnomalyType> clockOutTypes() {
        return List.of(AnomalyType.EARLY_LEAVE, AnomalyType.UNDER_STD_HOURS, AnomalyType.UNAPPROVED_OT);
    }

    /** 분 단위로 비교 (근로시간 계산과 동일하게 초는 버림) */
    private static LocalDateTime toLocal(OffsetDateTime t) {
        return t.atZoneSameInstant(DateTimeUtils.ZONE).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES);
    }
}