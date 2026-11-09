package com.ssafy.leaveservice.domain.ledger.controller;

import com.ssafy.leaveservice.domain.ledger.dto.MyLeaveLedgerResponse;
import com.ssafy.leaveservice.domain.ledger.service.MyLeaveLedgerService;
import com.ssafy.leaveservice.global.dto.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/leaves/me/ledger")
@RequiredArgsConstructor
public class MyLeaveLedgerController {

    private final MyLeaveLedgerService myLeaveLedgerService;

    // 내 휴가 원장 조회
    @GetMapping
    public ResponseEntity<ApiResult<List<MyLeaveLedgerResponse>>> getMyLedger() {
        List<MyLeaveLedgerResponse> response = myLeaveLedgerService.getMyLedger();
        return ResponseEntity.ok(ApiResult.success(response));
    }
}
