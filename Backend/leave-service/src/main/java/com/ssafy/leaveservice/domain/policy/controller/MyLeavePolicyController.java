package com.ssafy.leaveservice.domain.policy.controller;

import com.ssafy.leaveservice.domain.policy.dto.MyLeavePolicyResponse;
import com.ssafy.leaveservice.domain.policy.service.MyLeavePolicyService;
import com.ssafy.leaveservice.global.dto.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/leaves/me/policy")
@RequiredArgsConstructor
public class MyLeavePolicyController {

    private final MyLeavePolicyService myLeavePolicyService;

    // 현재 나에게 적용되는 휴가 정책 조회
    @GetMapping
    public ResponseEntity<ApiResult<MyLeavePolicyResponse>> getMyPolicy() {
        MyLeavePolicyResponse response = myLeavePolicyService.getMyPolicy();
        return ResponseEntity.ok(ApiResult.success(response));
    }
}
