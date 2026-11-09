package com.ssafy.attendanceservice.domain.attendance.dto;

import com.ssafy.attendanceservice.domain.attendance.entity.WorkGroup;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/** 월간 근태 (1일 ~ 말일) */
public record MonthlyAttendanceResponse(
        YearMonth month,                            // 조회 월
        LocalDate startDate,                        // 1일
        LocalDate endDate,                          // 말일
        String workGroupName,                       // 근무그룹명
        Summary summary,                            // 월간 합계
        List<AttendanceDaySummaryResponse> days     // 날짜별
) {

    public record Summary(
            int totalMin,           // 월간 누적 근로시간
            int approvedOtMin,      // 승인 초과근로
            int unapprovedOtMin,    // 미승인 초과근로
            int stdMin,             // 월 소정 근로시간 (근무일 수 × 일 소정)
            int remainStdMin,       // 잔여 소정 근로시간
            int workDays,           // 이번 달 근무일 수
            int remainWorkDays      // 오늘 포함 남은 근무일 수
    ) {
    }

    public static MonthlyAttendanceResponse of(YearMonth month, WorkGroup workGroup,
                                               List<AttendanceDaySummaryResponse> days, LocalDate today) {
        int totalMin = days.stream().mapToInt(d -> d.workTime().totalMin()).sum();
        int workDays = (int) days.stream().filter(d -> !d.isHoliday()).count();
        int stdMin = workDays * workGroup.getDailyStdMinutes();

        Summary summary = new Summary(
                totalMin,
                days.stream().mapToInt(d -> d.workTime().approvedOtMin()).sum(),
                days.stream().mapToInt(d -> d.workTime().unapprovedOtMin()).sum(),
                stdMin,
                Math.max(0, stdMin - totalMin),
                workDays,
                (int) days.stream().filter(d -> !d.isHoliday() && !d.workDate().isBefore(today)).count()
        );
        return new MonthlyAttendanceResponse(
                month, month.atDay(1), month.atEndOfMonth(), workGroup.getName(), summary, days);
    }
}
