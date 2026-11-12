package com.ssafy.attendanceservice.domain.attendance.dto;

import com.ssafy.attendanceservice.domain.attendance.entity.WorkGroup;

import java.time.LocalDate;
import java.util.List;

/** 주간 근태 (월~일) */
public record WeeklyAttendanceResponse(
        LocalDate startDate,                        // 주 시작일 (월)
        LocalDate endDate,                          // 주 종료일 (일)
        String workGroupName,                       // 근무그룹명
        Summary summary,                            // 주간 합계
        List<AttendanceDaySummaryResponse> days     // 요일별 (7일)
) {

    public record Summary(
            int totalMin,           // 주간 누적 근로시간
            int approvedOtMin,      // 승인 초과근로
            int unapprovedOtMin,    // 미승인 초과근로
            int stdMin,             // 주 소정 근로시간
            int maxMin,             // 주 최대 근로시간
            int remainStdMin,       // 잔여 소정 근로시간
            int workDays,           // 이번 주 근무일 수
            int remainWorkDays      // 오늘 포함 남은 근무일 수
    ) {
    }

    public static WeeklyAttendanceResponse of(LocalDate startDate, LocalDate endDate, WorkGroup workGroup,
                                              List<AttendanceDaySummaryResponse> days, LocalDate today) {
        int totalMin = days.stream().mapToInt(d -> d.workTime().totalMin()).sum();
        int workDays = (int) days.stream().filter(d -> !d.isHoliday()).count();
        // 근무일 수 × 일 소정(월간과 같은 기준). 공휴일·휴일대체로 근무일이 줄면 줄고, 늘어도 주 소정 근로시간은 넘지 않는다
        int stdMin = Math.min(workGroup.getWeeklyStdHours() * 60, workDays * workGroup.getDailyStdMinutes());

        Summary summary = new Summary(
                totalMin,
                days.stream().mapToInt(d -> d.workTime().approvedOtMin()).sum(),
                days.stream().mapToInt(d -> d.workTime().unapprovedOtMin()).sum(),
                stdMin,
                workGroup.getWeeklyMaxHours() * 60,
                Math.max(0, stdMin - totalMin),
                workDays,
                (int) days.stream().filter(d -> !d.isHoliday() && !d.workDate().isBefore(today)).count()
        );
        return new WeeklyAttendanceResponse(startDate, endDate, workGroup.getName(), summary, days);
    }
}