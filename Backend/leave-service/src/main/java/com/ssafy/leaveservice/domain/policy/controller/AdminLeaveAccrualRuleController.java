package com.ssafy.leaveservice.domain.policy.controller;

import com.ssafy.leaveservice.domain.policy.dto.LeaveAccrualRuleResponse;
import com.ssafy.leaveservice.domain.policy.dto.LeaveAccrualRuleReplaceRequest;
import com.ssafy.leaveservice.domain.policy.service.LeaveAccrualRuleService;
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
@RequestMapping("/api/v1/leaves/admin/policies/{policyId}/versions/{versionId}/accrual-rules")
@RequiredArgsConstructor
public class AdminLeaveAccrualRuleController {

    private final LeaveAccrualRuleService leaveAccrualRuleService;

    // 정책 버전의 연차 발생 규칙 전체 교체
    @PutMapping
    public ResponseEntity<ApiResult<List<LeaveAccrualRuleResponse>>> replaceAccrualRules(
            @PathVariable Long policyId,
            @PathVariable Long versionId,
            @Valid @RequestBody LeaveAccrualRuleReplaceRequest request
    ) {
        List<LeaveAccrualRuleResponse> accrualRules = leaveAccrualRuleService.replaceAccrualRules(policyId, versionId, request);
        return ResponseEntity.ok(ApiResult.success(accrualRules));
    }

    // 정책 버전의 연차 발생 규칙 목록 조회
    @GetMapping
    public ResponseEntity<ApiResult<List<LeaveAccrualRuleResponse>>> getAccrualRules(
            @PathVariable Long policyId,
            @PathVariable Long versionId
    ) {
        List<LeaveAccrualRuleResponse> accrualRules = leaveAccrualRuleService.getAccrualRules(policyId, versionId);
        return ResponseEntity.ok(ApiResult.success(accrualRules));
    }
}
