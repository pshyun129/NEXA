package com.ssafy.leaveservice.domain.account.controller;

import com.ssafy.leaveservice.domain.account.dto.MyLeaveBalanceResponse;
import com.ssafy.leaveservice.domain.account.service.MyLeaveBalanceService;
import com.ssafy.leaveservice.global.dto.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/leaves/me/balances")
@RequiredArgsConstructor
public class MyLeaveBalanceController {

    private final MyLeaveBalanceService myLeaveBalanceService;

    // 내 휴가 잔액 유형별 잔여량 조회
    @GetMapping
    public ResponseEntity<ApiResult<List<MyLeaveBalanceResponse>>> getMyBalances() {
        List<MyLeaveBalanceResponse> response = myLeaveBalanceService.getMyBalances();
        return ResponseEntity.ok(ApiResult.success(response));
    }
}
