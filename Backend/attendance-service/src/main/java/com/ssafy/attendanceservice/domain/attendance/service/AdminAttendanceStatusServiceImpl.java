package com.ssafy.attendanceservice.domain.attendance.service;

import com.ssafy.attendanceservice.domain.attendance.client.EmployeeInfo;
import com.ssafy.attendanceservice.domain.attendance.dto.AdminDailyStatusResponse;
import com.ssafy.attendanceservice.domain.attendance.dto.AdminPeriodStatusResponse;
import com.ssafy.attendanceservice.domain.attendance.dto.AttendanceDaySummaryResponse;
import com.ssafy.attendanceservice.global.exception.BusinessException;
import com.ssafy.attendanceservice.global.exception.ErrorCode;
import com.ssafy.attendanceservice.global.util.DateTimeUtils;
import com.ssafy.nexa.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AdminAttendanceStatusServiceImpl implements AdminAttendanceStatusService {

    // 기간별 통계 최대 조회 기간 (시작일·종료일 포함, 한 분기)
    private static final int PERIOD_MAX_DAYS = 92;
    // user-service 재직 상태. 생략하면 재직자만
    private static final String EMPLOYED = "EMPLOYED";
    private static final Set<String> EMP_STATUSES = Set.of(EMPLOYED, "ON_LEAVE", "TERMINATED");

    private final AttendanceViewScope attendanceViewScope;
    private final AttendanceService attendanceService;

    @Override
    @Transactional(readOnly = true)
    public AdminDailyStatusResponse getDaily(AuthUser user, LocalDate date, Long deptId, String empStatus) {
        // 미래 날짜는 전원이 미출근으로 잡히므로 받지 않는다
        if (date.isAfter(DateTimeUtils.now().toLocalDate())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "미래 날짜는 조회할 수 없습니다.");
        }
        List<EmployeeInfo> employees = targets(user, deptId, empStatus);

        List<AttendanceDaySummaryResponse> days = employees.stream()
                .map(employee -> attendanceService.getDaySummaries(employee.empId(), date, date).getFirst())
                .toList();
        return AdminDailyStatusResponse.of(date, employees, days);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminPeriodStatusResponse getPeriod(AuthUser user, LocalDate from, LocalDate to, Long deptId,
                                               String empStatus) {
        if (from.isAfter(to)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "from은 to보다 늦을 수 없습니다.");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > PERIOD_MAX_DAYS) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "조회 기간은 최대 " + PERIOD_MAX_DAYS + "일입니다.");
        }
        List<EmployeeInfo> employees = targets(user, deptId, empStatus);

        List<List<AttendanceDaySummaryResponse>> daysByEmployee = employees.stream()
                .map(employee -> attendanceService.getDaySummaries(employee.empId(), from, to))
                .toList();
        return AdminPeriodStatusResponse.of(from, to, employees, daysByEmployee);
    }

    /** 볼 수 있는 사원 중 재직 상태가 맞는 사원 (사원 ID 순) */
    private List<EmployeeInfo> targets(AuthUser user, Long deptId, String empStatus) {
        String status = empStatus == null ? EMPLOYED : empStatus;
        if (!EMP_STATUSES.contains(status)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "empStatus는 " + EMP_STATUSES + " 중 하나입니다.");
        }
        return attendanceViewScope.employees(user, deptId).stream()
                .filter(employee -> status.equals(employee.status()))
                .sorted(Comparator.comparing(EmployeeInfo::empId))
                .toList();
    }
}
