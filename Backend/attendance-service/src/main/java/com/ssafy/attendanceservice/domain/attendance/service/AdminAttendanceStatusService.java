package com.ssafy.attendanceservice.domain.attendance.service;

import com.ssafy.attendanceservice.domain.attendance.dto.AdminDailyStatusResponse;
import com.ssafy.attendanceservice.domain.attendance.dto.AdminPeriodStatusResponse;
import com.ssafy.nexa.security.AuthUser;

import java.time.LocalDate;

/**
 * 관리자 근태 현황 (팀장급 이상). 볼 수 있는 사원은 AttendanceViewScope가 정한다.
 * deptId를 주면 그 부서와 하위 부서만, empStatus를 생략하면 재직자만
 */
public interface AdminAttendanceStatusService {

    /** 일자별 현황. 미래 날짜는 BusinessException(INVALID_INPUT) */
    AdminDailyStatusResponse getDaily(AuthUser user, LocalDate date, Long deptId, String empStatus);

    /** 기간별 통계. from이 to보다 늦거나 92일을 넘으면 BusinessException(INVALID_INPUT) */
    AdminPeriodStatusResponse getPeriod(AuthUser user, LocalDate from, LocalDate to, Long deptId, String empStatus);
}
