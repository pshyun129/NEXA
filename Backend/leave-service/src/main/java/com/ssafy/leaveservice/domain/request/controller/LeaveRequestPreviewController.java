package com.ssafy.leaveservice.domain.request.controller;

import com.ssafy.leaveservice.domain.request.dto.*;
import com.ssafy.leaveservice.domain.request.service.LeaveRequestPreviewService;
import com.ssafy.leaveservice.global.dto.ApiResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/leaves/me/requests/preview")
@RequiredArgsConstructor
public class LeaveRequestPreviewController {
    private final LeaveRequestPreviewService previewService;

    // 휴가 신청 전 차감 시간과 잔액 미리보기
    @PostMapping
    public ResponseEntity<ApiResult<LeaveRequestPreviewResponse>> preview(@Valid @RequestBody LeaveRequestPreviewRequest request) {
        LeaveRequestPreviewResponse response = previewService.preview(request);
        return ResponseEntity.ok(ApiResult.success(response));
    }
}
