package com.ssafy.leaveservice.domain.policy.controller;

import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyActivationRequest;
import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyActivationResponse;
import com.ssafy.leaveservice.domain.policy.service.LeavePolicyActivationService;
import com.ssafy.leaveservice.global.dto.ApiResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/leaves/admin/policies")
@RequiredArgsConstructor
public class AdminLeavePolicyActivationController {

    private final LeavePolicyActivationService leavePolicyActivationService;

    // 정책 그룹의 활성 버전 선택
    @PutMapping("/{policyId}/active-version")
    public ResponseEntity<ApiResult<LeavePolicyActivationResponse>> activateVersion(
            @PathVariable Long policyId,
            @Valid @RequestBody LeavePolicyActivationRequest request
    ) {
        LeavePolicyActivationResponse response = leavePolicyActivationService.activateVersion(policyId, request);
        return ResponseEntity.ok(ApiResult.success(response));
    }
}
