package com.ssafy.leaveservice.domain.adjustment.controller;

import com.ssafy.leaveservice.domain.adjustment.dto.LeaveAdjustmentRequest;
import com.ssafy.leaveservice.domain.adjustment.dto.LeaveAdjustmentResponse;
import com.ssafy.leaveservice.domain.adjustment.service.LeaveAdjustmentService;
import com.ssafy.leaveservice.global.dto.ApiResult;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/api/v1/leaves/admin/employees/{employeeId}/adjustments")
@RequiredArgsConstructor
public class AdminLeaveAdjustmentController {

    private final LeaveAdjustmentService adjustmentService;

    // 직원의 휴가 잔액 수동 조정
    @PostMapping
    public ResponseEntity<ApiResult<LeaveAdjustmentResponse>> adjust(
            @PathVariable Long employeeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestKey,
            @Valid @RequestBody LeaveAdjustmentRequest request
    ) {
        LeaveAdjustmentResponse response = adjustmentService.adjust(employeeId, requestKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.success(response));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResult<Void>> handleInvalidEmployeeId() {
        return ResponseEntity.badRequest().body(ApiResult.fail(ErrorCode.INVALID_INPUT));
    }
}
