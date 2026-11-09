package com.ssafy.leaveservice.domain.request.controller;

import com.ssafy.leaveservice.domain.request.dto.AdminLeaveRequestListResponse;
import com.ssafy.leaveservice.domain.request.dto.MyLeaveRequestDetailResponse;
import com.ssafy.leaveservice.domain.request.service.AdminLeaveRequestService;
import com.ssafy.leaveservice.global.dto.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/leaves/admin/requests")
@RequiredArgsConstructor
public class AdminLeaveRequestController {

    private final AdminLeaveRequestService adminLeaveRequestService;

    // 관리자용 휴가 신청 목록 조회
    @GetMapping
    public ResponseEntity<ApiResult<List<AdminLeaveRequestListResponse>>> getAllRequests() {
        List<AdminLeaveRequestListResponse> response = adminLeaveRequestService.getAllRequests();
        return ResponseEntity.ok(ApiResult.success(response));
    }

    // 관리자용 휴가 신청 상세 조회
    @GetMapping("/{requestId}")
    public ResponseEntity<ApiResult<MyLeaveRequestDetailResponse>> getRequest(@PathVariable String requestId) {
        MyLeaveRequestDetailResponse response = adminLeaveRequestService.getRequest(requestId);
        return ResponseEntity.ok(ApiResult.success(response));
    }
}
