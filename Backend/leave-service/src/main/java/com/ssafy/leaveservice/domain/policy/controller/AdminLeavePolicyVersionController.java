package com.ssafy.leaveservice.domain.policy.controller;

import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyVersionListResponse;
import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyVersionDetailResponse;
import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyVersionCreateRequest;
import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyVersionUpdateRequest;
import com.ssafy.leaveservice.domain.policy.service.LeavePolicyVersionService;
import com.ssafy.leaveservice.global.dto.ApiResult;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;

import java.util.List;

@RestController
@RequestMapping("/api/v1/leaves/admin/policies/{policyId}/versions")
@RequiredArgsConstructor
public class AdminLeavePolicyVersionController {

    private final LeavePolicyVersionService leavePolicyVersionService;

    // 휴가 정책 버전 삭제
    @DeleteMapping("/{versionId}")
    public ResponseEntity<ApiResult<Void>> deletePolicyVersion(
            @PathVariable Long policyId,
            @PathVariable Long versionId
    ) {
        leavePolicyVersionService.deletePolicyVersion(policyId, versionId);
        return ResponseEntity.ok(ApiResult.success(null));
    }

    // 휴가 정책 버전 수정
    @PatchMapping("/{versionId}")
    public ResponseEntity<ApiResult<LeavePolicyVersionDetailResponse>> updatePolicyVersion(
            @PathVariable Long policyId,
            @PathVariable Long versionId,
            @Valid @RequestBody LeavePolicyVersionUpdateRequest request
    ) {
        LeavePolicyVersionDetailResponse policyVersion = leavePolicyVersionService.updatePolicyVersion(policyId, versionId, request);
        return ResponseEntity.ok(ApiResult.success(policyVersion));
    }

    // 휴가 정책 버전 생성
    @PostMapping
    public ResponseEntity<ApiResult<LeavePolicyVersionDetailResponse>> createPolicyVersion(
            @PathVariable Long policyId,
            @Valid @RequestBody LeavePolicyVersionCreateRequest request
    ) {
        LeavePolicyVersionDetailResponse policyVersion = leavePolicyVersionService.createPolicyVersion(policyId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.success(policyVersion));
    }

    // 휴가 정책 버전 상세 조회
    @GetMapping("/{versionId}")
    public ResponseEntity<ApiResult<LeavePolicyVersionDetailResponse>> getPolicyVersion(
            @PathVariable Long policyId,
            @PathVariable Long versionId
    ) {
        LeavePolicyVersionDetailResponse policyVersion = leavePolicyVersionService.getPolicyVersion(policyId, versionId);
        return ResponseEntity.ok(ApiResult.success(policyVersion));
    }

    // 휴가 정책 버전 목록 조회
    @GetMapping
    public ResponseEntity<ApiResult<List<LeavePolicyVersionListResponse>>> getPolicyVersions(@PathVariable Long policyId) {
        List<LeavePolicyVersionListResponse> policyVersions = leavePolicyVersionService.getPolicyVersions(policyId);
        return ResponseEntity.ok(ApiResult.success(policyVersions));
    }
}
