package com.ssafy.leaveservice.domain.policy.controller;

import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyTypeRuleResponse;
import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyTypeRuleUpsertRequest;
import com.ssafy.leaveservice.domain.policy.service.LeavePolicyTypeRuleService;
import com.ssafy.leaveservice.global.dto.ApiResult;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@RestController
@RequestMapping("/api/v1/leaves/admin/policies/{policyId}/versions/{versionId}/type-rules")
@RequiredArgsConstructor
public class AdminLeavePolicyTypeRuleController {

    private final LeavePolicyTypeRuleService leavePolicyTypeRuleService;

    // 정책 버전의 특정 휴가 유형 규칙 저장
    @PutMapping("/{leaveTypeId}")
    public ResponseEntity<ApiResult<LeavePolicyTypeRuleResponse>> upsertTypeRule(
            @PathVariable Long policyId,
            @PathVariable Long versionId,
            @PathVariable Long leaveTypeId,
            @Valid @RequestBody LeavePolicyTypeRuleUpsertRequest request
    ) {
        LeavePolicyTypeRuleResponse response = leavePolicyTypeRuleService.upsertTypeRule(policyId, versionId, leaveTypeId, request);
        return ResponseEntity.ok(ApiResult.success(response));
    }

    // 정책 버전의 휴가 유형 규칙 목록 조회
    @GetMapping
    public ResponseEntity<ApiResult<List<LeavePolicyTypeRuleResponse>>> getTypeRules(
            @PathVariable Long policyId,
            @PathVariable Long versionId
    ) {
        List<LeavePolicyTypeRuleResponse> typeRules = leavePolicyTypeRuleService.getTypeRules(policyId, versionId);
        return ResponseEntity.ok(ApiResult.success(typeRules));
    }
}
