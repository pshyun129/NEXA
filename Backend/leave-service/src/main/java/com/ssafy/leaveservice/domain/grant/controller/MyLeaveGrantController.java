package com.ssafy.leaveservice.domain.grant.controller;

import com.ssafy.leaveservice.domain.grant.dto.MyLeaveGrantResponse;
import com.ssafy.leaveservice.domain.grant.service.MyLeaveGrantService;
import com.ssafy.leaveservice.global.dto.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/leaves/me/grants")
@RequiredArgsConstructor
public class MyLeaveGrantController {

    private final MyLeaveGrantService myLeaveGrantService;

    // 내 휴가 발생 내역 조회
    @GetMapping
    public ResponseEntity<ApiResult<List<MyLeaveGrantResponse>>> getMyGrants() {
        List<MyLeaveGrantResponse> response = myLeaveGrantService.getMyGrants();
        return ResponseEntity.ok(ApiResult.success(response));
    }
}
