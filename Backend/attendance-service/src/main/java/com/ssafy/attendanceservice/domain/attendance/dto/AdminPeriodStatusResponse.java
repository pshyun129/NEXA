package com.ssafy.attendanceservice.domain.attendance.dto;

import com.ssafy.attendanceservice.domain.attendance.client.EmployeeInfo;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AnomalyType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 관리자 기간별 근태 통계: 전체 합계 + 사원별 합계. 합계 항목은 급여용 월별 집계와 같다 */
public record AdminPeriodStatusResponse(
        LocalDate from,                 // 시작일
        LocalDate to,                   // 종료일
        Total total,                    // 전체 합계
        List<Employee> employees        // 사원별 합계 (사원 ID 순)
) {

    public record Total(
            int employeeCount,                                  // 대상 사원 수
            AttendanceSummaryResponse.Days days,                // 일수 합계
            AttendanceSummaryResponse.WorkTime workTime,        // 근로시간 합계 (분)
            AttendanceSummaryResponse.Leave leave,              // 휴가 합계
            Map<AnomalyType, Integer> anomalies                 // 근태이상 유형별 횟수
    ) {
    }

    public record Employee(
            Long empId,                                         // 사원 ID
            String name,                                        // 이름
            Long deptId,                                        // 부서 ID
            String deptName,                                    // 부서명
            AttendanceSummaryResponse.Days days,                // 일수
            AttendanceSummaryResponse.WorkTime workTime,        // 근로시간 (분)
            AttendanceSummaryResponse.Leave leave,              // 휴가
            Map<AnomalyType, Integer> anomalies                 // 근태이상 유형별 횟수
    ) {
        static Employee of(EmployeeInfo employee, List<AttendanceDaySummaryResponse> days) {
            return new Employee(employee.empId(), employee.name(), employee.deptId(), employee.deptName(),
                    AttendanceSummaryResponse.Days.of(days),
                    AttendanceSummaryResponse.WorkTime.of(days),
                    AttendanceSummaryResponse.Leave.of(days),
                    AttendanceSummaryResponse.anomaliesOf(days));
        }
    }

    /** @param daysByEmployee 사원 순서와 같은 순서의 기간 내 날짜별 근태 요약 */
    public static AdminPeriodStatusResponse of(LocalDate from, LocalDate to, List<EmployeeInfo> employees,
                                               List<List<AttendanceDaySummaryResponse>> daysByEmployee) {
        List<Employee> rows = new ArrayList<>(employees.size());
        for (int i = 0; i < employees.size(); i++) {
            rows.add(Employee.of(employees.get(i), daysByEmployee.get(i)));
        }

        // 전체 합계는 모든 사원의 날짜를 한데 모아 같은 방식으로 센다
        List<AttendanceDaySummaryResponse> allDays = daysByEmployee.stream().flatMap(List::stream).toList();
        Total total = new Total(
                rows.size(),
                AttendanceSummaryResponse.Days.of(allDays),
                AttendanceSummaryResponse.WorkTime.of(allDays),
                AttendanceSummaryResponse.Leave.of(allDays),
                AttendanceSummaryResponse.anomaliesOf(allDays));
        return new AdminPeriodStatusResponse(from, to, total, rows);
    }
}
