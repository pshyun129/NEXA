package com.ssafy.leaveservice.domain.request.controller;

import com.ssafy.leaveservice.domain.request.dto.MyLeaveRequestListResponse;
import com.ssafy.leaveservice.domain.request.dto.MyLeaveRequestDetailResponse;
import com.ssafy.leaveservice.domain.request.service.MyLeaveRequestService;
import com.ssafy.leaveservice.global.dto.ApiResult;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/leaves/me/requests")
@RequiredArgsConstructor
public class MyLeaveRequestController {

    private final MyLeaveRequestService myLeaveRequestService;

    // 내 휴가 신청 목록 조회
    @GetMapping
    public ResponseEntity<ApiResult<List<MyLeaveRequestListResponse>>> getMyRequests() {
        List<MyLeaveRequestListResponse> response = myLeaveRequestService.getMyRequests();
        return ResponseEntity.ok(ApiResult.success(response));
    }

    // 내 휴가 신청 상세 조회
    @GetMapping("/{requestId}")
    public ResponseEntity<ApiResult<MyLeaveRequestDetailResponse>> getMyRequest(@PathVariable Long requestId) {
        MyLeaveRequestDetailResponse response = myLeaveRequestService.getMyRequest(requestId);
        return ResponseEntity.ok(ApiResult.success(response));
    }

    // 기존 전역 예외 처리에 영향을 주지 않고 잘못된 신청 ID 형식만 400으로 반환한다.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResult<Void>> handleInvalidRequestId() {
        return ResponseEntity.badRequest().body(ApiResult.fail(ErrorCode.INVALID_INPUT));
    }
}
