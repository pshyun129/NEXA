package com.ssafy.attendanceservice.domain.attendance.controller;

import com.ssafy.attendanceservice.domain.attendance.dto.*;
import com.ssafy.attendanceservice.domain.attendance.service.AttendanceService;
import com.ssafy.attendanceservice.global.dto.ApiResult;
import com.ssafy.nexa.security.AuthUser;
import com.ssafy.nexa.security.CurrentEmployeeId;
import com.ssafy.nexa.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/** 본인 근태 API */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/attn")
public class AttendanceController {

    private final AttendanceService attendanceService;

    // 출근 API
    @PostMapping("/clock-in")
    public ResponseEntity<ApiResult<AttendanceStatusResponse>> clockIn(@CurrentEmployeeId Long empId) {
        return ResponseEntity.ok(ApiResult.success(attendanceService.clockIn(empId)));
    }

    // 퇴근 API
    @PostMapping("/clock-out")
    public ResponseEntity<ApiResult<AttendanceStatusResponse>> clockOut(@CurrentEmployeeId Long empId) {
        return ResponseEntity.ok(ApiResult.success(attendanceService.clockOut(empId)));
    }

    // 근무상태 변경 API
    @PostMapping("/status")
    public ResponseEntity<ApiResult<AttendanceStatusResponse>> changeStatus(@CurrentEmployeeId Long empId,
                                                                            @Valid @RequestBody WorkStatusChangeRequest request) {
        return ResponseEntity.ok(ApiResult.success(attendanceService.changeStatus(empId, request.statusCode())));
    }

    // 현재 근무 상태 조회 API
    @GetMapping("/today")
    public ResponseEntity<ApiResult<AttendanceStatusResponse>> getToday(@CurrentEmployeeId Long empId) {
        return ResponseEntity.ok(ApiResult.success(attendanceService.getToday(empId)));
    }

    // 일별 근태 타임라인 API
    @GetMapping("/me/daily/{date}")
    public ResponseEntity<ApiResult<DailyAttendanceResponse>> getDaily(
            @CurrentEmployeeId Long empId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResult.success(attendanceService.getDaily(empId, date)));
    }

    // 주간 근태 조회 API
    @GetMapping("/me/weekly")
    public ResponseEntity<ApiResult<WeeklyAttendanceResponse>> getWeekly(
            @CurrentEmployeeId Long empId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResult.success(attendanceService.getWeekly(empId, date)));
    }

    // 월간 근태 조회 API
    @GetMapping("/me/monthly")
    public ResponseEntity<ApiResult<MonthlyAttendanceResponse>> getMonthly(
            @CurrentEmployeeId Long empId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return ResponseEntity.ok(ApiResult.success(attendanceService.getMonthly(empId, month)));
    }

    // 근무상태 변경 이력 조회 API
    @GetMapping("/me/history")
    public ResponseEntity<ApiResult<List<WorkStatusHistoryResponse>>> getHistory(
            @CurrentEmployeeId Long empId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResult.success(attendanceService.getHistory(empId, from, to)));
    }

    // 월별 근태 집계 API (급여 서비스 연동용, 본인·상급자·관리자만)
    @GetMapping("/summary")
    public ResponseEntity<ApiResult<AttendanceSummaryResponse>> getSummary(
            @CurrentUser AuthUser user,
            @RequestParam Long empId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return ResponseEntity.ok(ApiResult.success(attendanceService.getSummary(user, empId, month)));
    }

    // 근무 상태 코드 목록 API
    @GetMapping("/status-codes")
    public ResponseEntity<ApiResult<List<WorkStatusCodeResponse>>> getStatusCodes() {
        return ResponseEntity.ok(ApiResult.success(attendanceService.getStatusCodes()));
    }

    // 휴가를 쓸 수 없는 날 조회 API (휴가 신청 검증용, 공휴일 + 내 대체휴일)
    @GetMapping("/holidays")
    public ResponseEntity<ApiResult<List<HolidayResponse>>> getHolidays(
            @CurrentEmployeeId Long empId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(ApiResult.success(attendanceService.getHolidays(empId, startDate, endDate)));
    }
}