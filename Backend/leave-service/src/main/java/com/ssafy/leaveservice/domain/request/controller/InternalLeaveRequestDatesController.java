package com.ssafy.leaveservice.domain.request.controller;

import com.ssafy.leaveservice.domain.request.dto.LeaveRequestDatesResponse;
import com.ssafy.leaveservice.domain.request.service.LeaveRequestDatesService;
import com.ssafy.leaveservice.global.dto.ApiResult;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/leaves/internal/requests")
public class InternalLeaveRequestDatesController {
    private final LeaveRequestDatesService datesService;

    // 근태 연동용 휴가 신청 날짜와 신청 단위 조회
    @GetMapping("/{requestId}/dates")
    public ApiResult<LeaveRequestDatesResponse> getDates(@PathVariable Long requestId) {
        return ApiResult.success(datesService.getDates(requestId));
    }

    // 이 내부 API의 숫자가 아닌 신청 ID도 기존 입력 오류 규격으로 반환한다.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResult<Void>> handleInvalidRequestId(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest().body(ApiResult.fail(ErrorCode.INVALID_INPUT));
    }
}
