package com.ssafy.leaveservice.domain.account.controller;

import com.ssafy.leaveservice.domain.account.dto.MyLeaveSummaryResponse;
import com.ssafy.leaveservice.domain.account.service.MyLeaveBalanceService;
import com.ssafy.leaveservice.global.dto.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/leaves/me/summary")
@RequiredArgsConstructor
public class MyLeaveSummaryController {

    private final MyLeaveBalanceService myLeaveBalanceService;

    // 내 휴가 잔액 요약 조회
    @GetMapping
    public ResponseEntity<ApiResult<MyLeaveSummaryResponse>> getMySummary() {
        MyLeaveSummaryResponse response = myLeaveBalanceService.getMySummary();
        return ResponseEntity.ok(ApiResult.success(response));
    }
}
