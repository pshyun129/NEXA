package com.ssafy.leaveservice.domain.grant.controller;

import com.ssafy.leaveservice.domain.grant.dto.ManualLeaveGrantRequest;
import com.ssafy.leaveservice.domain.grant.dto.ManualLeaveGrantResponse;
import com.ssafy.leaveservice.domain.grant.service.ManualLeaveGrantService;
import com.ssafy.leaveservice.global.dto.ApiResult;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
@RequestMapping("/api/v1/leaves/admin/employees/{employeeId}/grants")
public class AdminManualLeaveGrantController {
    private final ManualLeaveGrantService grantService;

    // 직원에게 최초 또는 추가 수동 휴가 지급
    @PostMapping
    public ResponseEntity<ApiResult<ManualLeaveGrantResponse>> grant(
            @PathVariable Long employeeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestKey,
            @Valid @RequestBody ManualLeaveGrantRequest request
    ) {
        ManualLeaveGrantResponse response = grantService.grant(employeeId, requestKey, request);
        return ResponseEntity.status(201).body(ApiResult.success(response));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResult<Void>> handleInvalidEmployeeId() {
        return ResponseEntity.badRequest().body(ApiResult.fail(ErrorCode.INVALID_INPUT));
    }
}
