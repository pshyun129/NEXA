package com.ssafy.leaveservice.domain.request.controller;

import com.ssafy.leaveservice.domain.request.dto.LeaveRequestPreviewRequest;
import com.ssafy.leaveservice.domain.request.dto.LeaveSubmissionResponse;
import com.ssafy.leaveservice.domain.request.dto.LeaveSubmissionResult;
import com.ssafy.leaveservice.domain.request.service.LeaveSubmissionOrchestrationService;
import com.ssafy.leaveservice.global.dto.ApiResult;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/leaves/me/requests")
@RequiredArgsConstructor
public class LeaveSubmissionController {
    private final LeaveSubmissionOrchestrationService orchestrationService;

    // 휴가 신청 제출
    @PostMapping
    public ResponseEntity<ApiResult<LeaveSubmissionResponse>> submit(
            @RequestHeader("Idempotency-Key") String submissionKey,
            @Valid @RequestBody LeaveRequestPreviewRequest request
    ) {
        LeaveSubmissionResult result = orchestrationService.submit(submissionKey, request);
        LeaveSubmissionResponse response = LeaveSubmissionResponse.from(result);
        HttpStatus status = switch (result.status()) {
            case SUBMITTED -> HttpStatus.CREATED;
            case SUBMITTING -> HttpStatus.ACCEPTED;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(status).body(ApiResult.success(response));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResult<Void>> handleMissingSubmissionKey() {
        return ResponseEntity.badRequest().body(ApiResult.fail(ErrorCode.INVALID_INPUT));
    }
}
