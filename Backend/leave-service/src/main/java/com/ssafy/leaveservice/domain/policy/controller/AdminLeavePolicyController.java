package com.ssafy.leaveservice.domain.policy.controller;

import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyGroupResponse;
import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyGroupCreateRequest;
import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyGroupUpdateRequest;
import com.ssafy.leaveservice.domain.policy.service.LeavePolicyGroupService;
import com.ssafy.leaveservice.global.dto.ApiResult;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/leaves/admin/policies")
@RequiredArgsConstructor
public class AdminLeavePolicyController {

    private final LeavePolicyGroupService leavePolicyGroupService;

    // 휴가 정책 그룹 목록 조회
    @GetMapping
    public ResponseEntity<ApiResult<List<LeavePolicyGroupResponse>>> getAllPolicies() {
        List<LeavePolicyGroupResponse> policyGroups = leavePolicyGroupService.getAllPolicies();
        return ResponseEntity.ok(ApiResult.success(policyGroups));
    }

    // 휴가 정책 그룹 상세 조회
    @GetMapping("/{policyId}")
    public ResponseEntity<ApiResult<LeavePolicyGroupResponse>> getPolicyGroup(@PathVariable Long policyId) {
        LeavePolicyGroupResponse response = leavePolicyGroupService.getPolicyGroup(policyId);
        return ResponseEntity.ok(ApiResult.success(response));
    }

    // 휴가 정책 그룹 생성
    @PostMapping
    public ResponseEntity<ApiResult<LeavePolicyGroupResponse>> createPolicyGroup(
            @Valid @RequestBody LeavePolicyGroupCreateRequest request
    ) {
        LeavePolicyGroupResponse response = leavePolicyGroupService.createPolicyGroup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.success(response));
    }

    // 휴가 정책 그룹 이름 수정
    @PatchMapping("/{policyId}")
    public ResponseEntity<ApiResult<LeavePolicyGroupResponse>> updatePolicyGroup(
            @PathVariable Long policyId,
            @Valid @RequestBody LeavePolicyGroupUpdateRequest request
    ) {
        LeavePolicyGroupResponse response = leavePolicyGroupService.updatePolicyGroup(policyId, request);
        return ResponseEntity.ok(ApiResult.success(response));
    }
}
