package com.ssafy.leaveservice.domain.request.controller;

import com.ssafy.leaveservice.domain.request.dto.AdminLeaveCancellationRequest;
import com.ssafy.leaveservice.domain.request.dto.AdminLeaveCancellationResponse;
import com.ssafy.leaveservice.domain.request.entity.AdminCancellationStatus;
import com.ssafy.leaveservice.domain.request.service.AdminLeaveCancellationService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/api/v1/leaves/admin/requests")
@RequiredArgsConstructor
public class AdminLeaveCancellationController {
    private final AdminLeaveCancellationService cancellationService;

    // 관리자가 휴가 신청을 직접 취소
    @PostMapping("/{requestId}/cancel")
    public ResponseEntity<ApiResult<AdminLeaveCancellationResponse>> cancel(
            @PathVariable Long requestId,
            @Valid @RequestBody AdminLeaveCancellationRequest request
    ) {
        AdminLeaveCancellationResponse response = cancellationService.cancel(requestId, request);
        HttpStatus status = response.adminCancelStatus() == AdminCancellationStatus.COMPLETED
                ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(ApiResult.success(response));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResult<Void>> handleInvalidRequestId() {
        return ResponseEntity.badRequest().body(ApiResult.fail(ErrorCode.INVALID_INPUT));
    }
}
