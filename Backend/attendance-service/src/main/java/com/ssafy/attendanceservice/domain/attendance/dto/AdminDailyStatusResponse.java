package com.ssafy.attendanceservice.domain.attendance.dto;

import com.ssafy.attendanceservice.domain.attendance.client.EmployeeInfo;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AnomalyType;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.LeavePortion;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 관리자 일자별 근태 현황: 상단 집계 + 사원별 목록 */
public record AdminDailyStatusResponse(
        LocalDate date,                 // 조회 일자
        Summary summary,                // 상단 집계
        List<Employee> employees        // 사원별 근태 (사원 ID 순)
) {

    /** 그날 사원 상태. 한 사원은 하나에만 속한다 */
    public enum DayStatus {
        WORKED,     // 출근
        LEAVE,      // 종일 휴가 (출근 안 함)
        HOLIDAY,    // 휴일 (출근 안 함)
        ABSENT      // 미출근
    }

    public record Summary(
            int targetCount,                    // 대상 사원 수
            int workedCount,                    // 출근
            int leaveCount,                     // 종일 휴가
            int holidayCount,                   // 휴일
            int absentCount,                    // 미출근
            Map<AnomalyType, Integer> anomalies // 근태이상 유형별 인원
    ) {
    }

    public record Employee(
            Long empId,                     // 사원 ID
            String name,                    // 이름
            Long deptId,                    // 부서 ID
            String deptName,                // 부서명
            DayStatus status,               // 그날 상태
            OffsetDateTime clockIn,         // 출근 시각
            OffsetDateTime clockOut,        // 퇴근 시각
            WorkTimeResponse workTime,      // 근로시간 상세
            List<LeaveResponse> leaves,     // 휴가 (반차 포함)
            List<AnomalyType> anomalies     // 근태이상
    ) {
        static Employee of(EmployeeInfo employee, AttendanceDaySummaryResponse day) {
            return new Employee(employee.empId(), employee.name(), employee.deptId(), employee.deptName(),
                    statusOf(day), day.clockIn(), day.clockOut(), day.workTime(), day.leaves(), day.anomalies());
        }
    }

    /** @param days 사원 순서와 같은 순서의 그날 근태 요약 */
    public static AdminDailyStatusResponse of(LocalDate date, List<EmployeeInfo> employees,
                                              List<AttendanceDaySummaryResponse> days) {
        List<Employee> rows = new ArrayList<>(employees.size());
        for (int i = 0; i < employees.size(); i++) {
            rows.add(Employee.of(employees.get(i), days.get(i)));
        }

        Summary summary = new Summary(
                rows.size(),
                count(rows, DayStatus.WORKED),
                count(rows, DayStatus.LEAVE),
                count(rows, DayStatus.HOLIDAY),
                count(rows, DayStatus.ABSENT),
                AttendanceSummaryResponse.anomaliesOf(days));
        return new AdminDailyStatusResponse(date, summary, rows);
    }

    private static DayStatus statusOf(AttendanceDaySummaryResponse day) {
        if (day.clockIn() != null) {
            return DayStatus.WORKED;
        }
        if (day.leaveMin() >= LeavePortion.FULL.minutes()) {
            return DayStatus.LEAVE;
        }
        return day.isHoliday() ? DayStatus.HOLIDAY : DayStatus.ABSENT;
    }

    private static int count(List<Employee> rows, DayStatus status) {
        return (int) rows.stream().filter(row -> row.status() == status).count();
    }
}
