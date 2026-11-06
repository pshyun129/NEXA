package com.ssafy.attendanceservice.domain.attendance.controller;

import com.ssafy.attendanceservice.domain.attendance.dto.AdminDailyStatusResponse;
import com.ssafy.attendanceservice.domain.attendance.dto.AdminPeriodStatusResponse;
import com.ssafy.attendanceservice.domain.attendance.dto.AdminWorkStatusLogRequest;
import com.ssafy.attendanceservice.domain.attendance.dto.AdminWorkStatusLogUpdateRequest;
import com.ssafy.attendanceservice.domain.attendance.dto.DailyAttendanceResponse;
import com.ssafy.attendanceservice.domain.attendance.service.AdminAttendanceService;
import com.ssafy.attendanceservice.domain.attendance.service.AdminAttendanceStatusService;
import com.ssafy.attendanceservice.domain.attendance.service.AttendanceService;
import com.ssafy.attendanceservice.global.dto.ApiResult;
import com.ssafy.nexa.security.AuthUser;
import com.ssafy.nexa.security.CurrentEmployeeId;
import com.ssafy.nexa.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/** 관리자 근태 API. 근태 현황(daily, period)은 팀장급 이상, 그 외는 관리자(role ADMIN) */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/attn/admin")
public class AdminAttendanceController {

    private final AttendanceService attendanceService;
    private final AdminAttendanceService adminAttendanceService;
    private final AdminAttendanceStatusService adminAttendanceStatusService;

    // 일자별 근태 현황 API (팀장급 이상, 볼 수 있는 부서만)
    @GetMapping("/daily")
    public ResponseEntity<ApiResult<AdminDailyStatusResponse>> getDailyStatus(
            @CurrentUser AuthUser user,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) String empStatus) {
        return ResponseEntity.ok(ApiResult.success(adminAttendanceStatusService.getDaily(user, date, deptId, empStatus)));
    }

    // 기간별 근태 통계 API (팀장급 이상, 최대 92일)
    @GetMapping("/period")
    public ResponseEntity<ApiResult<AdminPeriodStatusResponse>> getPeriodStatus(
            @CurrentUser AuthUser user,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) String empStatus) {
        return ResponseEntity.ok(ApiResult.success(
                adminAttendanceStatusService.getPeriod(user, from, to, deptId, empStatus)));
    }

    // 직원 일별 타임라인 조회 API
    @GetMapping("/employees/{empId}/daily/{date}")
    public ResponseEntity<ApiResult<DailyAttendanceResponse>> getEmployeeDaily(
            @PathVariable Long empId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResult.success(attendanceService.getDaily(empId, date)));
    }

    // 근무 상태 직접 등록 API
    @PostMapping("/logs")
    public ResponseEntity<ApiResult<DailyAttendanceResponse>> registerLog(
            @CurrentEmployeeId Long adminId,
            @Valid @RequestBody AdminWorkStatusLogRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success(adminAttendanceService.registerLog(adminId, request)));
    }

    // 근무 상태 로그 수정 API
    @PutMapping("/logs/{logId}")
    public ResponseEntity<ApiResult<DailyAttendanceResponse>> updateLog(
            @CurrentEmployeeId Long adminId,
            @PathVariable Long logId,
            @Valid @RequestBody AdminWorkStatusLogUpdateRequest request) {
        return ResponseEntity.ok(ApiResult.success(adminAttendanceService.updateLog(adminId, logId, request)));
    }

    // 근무 상태 로그 삭제 API
    @DeleteMapping("/logs/{logId}")
    public ResponseEntity<ApiResult<DailyAttendanceResponse>> deleteLog(@PathVariable Long logId) {
        return ResponseEntity.ok(ApiResult.success(adminAttendanceService.deleteLog(logId)));
    }
}