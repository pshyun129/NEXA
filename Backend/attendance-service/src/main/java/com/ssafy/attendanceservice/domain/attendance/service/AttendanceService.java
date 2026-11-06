package com.ssafy.attendanceservice.domain.attendance.service;

import com.ssafy.attendanceservice.domain.attendance.dto.AttendanceDaySummaryResponse;
import com.ssafy.attendanceservice.domain.attendance.dto.AttendanceStatusResponse;
import com.ssafy.attendanceservice.domain.attendance.dto.AttendanceSummaryResponse;
import com.ssafy.attendanceservice.domain.attendance.dto.DailyAttendanceResponse;
import com.ssafy.attendanceservice.domain.attendance.dto.HolidayResponse;
import com.ssafy.attendanceservice.domain.attendance.dto.MonthlyAttendanceResponse;
import com.ssafy.attendanceservice.domain.attendance.dto.WeeklyAttendanceResponse;
import com.ssafy.attendanceservice.domain.attendance.dto.WorkStatusCodeResponse;
import com.ssafy.attendanceservice.domain.attendance.dto.WorkStatusHistoryResponse;
import com.ssafy.nexa.security.AuthUser;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

public interface AttendanceService {

    /** 출근. 오늘 이미 출근했으면 BusinessException(ALREADY_CLOCKED_IN) */
    AttendanceStatusResponse clockIn(Long empId);

    /** 퇴근. 출근 기록이 없거나 이미 퇴근했으면 BusinessException */
    AttendanceStatusResponse clockOut(Long empId);

    /** 근무상태 변경. 퇴근 상태에서 업무로 바꾸면 퇴근취소. 바꿀 수 없는 상태면 BusinessException */
    AttendanceStatusResponse changeStatus(Long empId, String statusCode);

    /** 현재 근무 상태 조회 (근태 위젯) */
    AttendanceStatusResponse getToday(Long empId);

    /** 근무 상태 코드 전체 목록 */
    List<WorkStatusCodeResponse> getStatusCodes();

    /**
     * 기간 내 휴가를 쓸 수 없는 날 (날짜순): 공휴일 + 내 대체휴일 - 휴일대체로 근무일이 된 공휴일.
     * startDate가 endDate보다 늦으면 BusinessException(INVALID_INPUT)
     */
    List<HolidayResponse> getHolidays(Long empId, LocalDate startDate, LocalDate endDate);

    /** 일별 근태 타임라인. 기록이 없는 날은 빈 응답 */
    DailyAttendanceResponse getDaily(Long empId, LocalDate date);

    /** 주간 근태 (date가 포함된 월~일) */
    WeeklyAttendanceResponse getWeekly(Long empId, LocalDate date);

    /** 월간 근태 (1일 ~ 말일) */
    MonthlyAttendanceResponse getMonthly(Long empId, YearMonth month);

    /** 근무상태 변경 이력 (삭제 포함). from이 to보다 늦거나 기간이 31일을 넘으면 BusinessException(INVALID_INPUT) */
    List<WorkStatusHistoryResponse> getHistory(Long empId, LocalDate from, LocalDate to);

    /** 급여용 월별 근태 집계 */
    AttendanceSummaryResponse getSummary(Long empId, YearMonth month);

    /**
     * 급여용 월별 근태 집계 (요청한 사원 기준 권한 확인).
     * 본인, 조회 범위 안의 사원, 관리자 토큰(급여 명세서 생성)만 볼 수 있다. 아니면 BusinessException(ACCESS_DENIED)
     */
    AttendanceSummaryResponse getSummary(AuthUser user, Long empId, YearMonth month);

    /** 기간 내 날짜별 근태 요약 (기록이 없는 날도 포함). 관리자 현황이 사원마다 쓴다 */
    List<AttendanceDaySummaryResponse> getDaySummaries(Long empId, LocalDate from, LocalDate to);
}