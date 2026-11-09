package com.ssafy.attendanceservice.domain.attendance.controller;

import com.ssafy.attendanceservice.domain.attendance.dto.AttendanceRequestDetailResponse;
import com.ssafy.attendanceservice.domain.attendance.dto.AttendanceRequestPreviewRequest;
import com.ssafy.attendanceservice.domain.attendance.dto.AttendanceRequestPreviewResponse;
import com.ssafy.attendanceservice.domain.attendance.dto.AttendanceRequestSubmitRequest;
import com.ssafy.attendanceservice.domain.attendance.dto.AttendanceRequestSummaryResponse;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AttendanceRequestType;
import com.ssafy.attendanceservice.domain.attendance.service.AttendanceRequestService;
import com.ssafy.attendanceservice.global.dto.ApiResult;
import com.ssafy.nexa.security.CurrentEmployeeId;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 본인 근태 신청서 API */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/attn/me/requests")
public class AttendanceRequestController {

    private final AttendanceRequestService attendanceRequestService;

    // 신청 검증 및 한도 계산 API
    @PostMapping("/preview")
    public ResponseEntity<ApiResult<AttendanceRequestPreviewResponse>> preview(
            @CurrentEmployeeId Long empId,
            @Valid @RequestBody AttendanceRequestPreviewRequest request) {
        return ResponseEntity.ok(ApiResult.success(attendanceRequestService.preview(empId, request)));
    }

    // 신청서 상신 API
    @PostMapping
    public ResponseEntity<ApiResult<AttendanceRequestDetailResponse>> submit(
            @CurrentEmployeeId Long empId,
            @Valid @RequestBody AttendanceRequestSubmitRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success(attendanceRequestService.submit(empId, request)));
    }

    // 내 신청 이력 API
    @GetMapping
    public ResponseEntity<ApiResult<List<AttendanceRequestSummaryResponse>>> getMyRequests(
            @CurrentEmployeeId Long empId,
            @RequestParam int year,
            @RequestParam(required = false) AttendanceRequestType requestType) {
        return ResponseEntity.ok(ApiResult.success(attendanceRequestService.getMyRequests(empId, year, requestType)));
    }

    // 신청서 상세 API
    @GetMapping("/{requestId}")
    public ResponseEntity<ApiResult<AttendanceRequestDetailResponse>> getMyRequest(
            @CurrentEmployeeId Long empId,
            @PathVariable Long requestId) {
        return ResponseEntity.ok(ApiResult.success(attendanceRequestService.getMyRequest(empId, requestId)));
    }

    // 신청서 회수 API (결재 대기 중이고 아직 아무도 결재하지 않은 신청서)
    @PostMapping("/{requestId}/cancel")
    public ResponseEntity<ApiResult<AttendanceRequestDetailResponse>> cancel(
            @CurrentEmployeeId Long empId,
            @PathVariable Long requestId) {
        return ResponseEntity.ok(ApiResult.success(attendanceRequestService.cancel(empId, requestId)));
    }
}
