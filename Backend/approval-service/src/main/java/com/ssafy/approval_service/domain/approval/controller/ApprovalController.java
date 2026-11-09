package com.ssafy.approval_service.domain.approval.controller;

import com.ssafy.approval_service.domain.approval.model.dto.ApprovalHistoryResponse;
import com.ssafy.approval_service.domain.approval.model.dto.RejectRequest;
import com.ssafy.approval_service.domain.approval.model.dto.ResubmitRequest;
import com.ssafy.approval_service.domain.approval.service.ApprovalService;
import com.ssafy.approval_service.global.dto.ApiResult;
import com.ssafy.approval_service.global.security.SecurityConstants;
import com.ssafy.nexa.security.AuthUser;
import com.ssafy.nexa.security.CurrentEmployeeId;
import com.ssafy.nexa.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 문서 단위 결재 API. 경로는 문서 하위 리소스로 두고, 결재선/이력 처리는 결재 도메인에서 담당한다.
 * 행위자는 access 토큰의 사원 ID로 식별하며, 차례/권한 검증은 서비스에서 한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping(SecurityConstants.DOCUMENT_BASE_PATH + "/{documentId}")
public class ApprovalController {

    private final ApprovalService approvalService;

    /** 문서의 결재 이력 (발생 순) */
    @GetMapping("/histories")
    public ResponseEntity<ApiResult<List<ApprovalHistoryResponse>>> getApprovalHistories(
            @CurrentUser AuthUser requester,
            @PathVariable Long documentId
    ) {
        List<ApprovalHistoryResponse> response = approvalService.getHistories(documentId, requester);
        return ResponseEntity.ok(ApiResult.success(response));
    }

    /** 결재 승인 (현재 차례의 결재자) */
    @PostMapping("/approve")
    public ResponseEntity<ApiResult<Void>> approve(
            @CurrentEmployeeId Long employeeId,
            @PathVariable Long documentId
    ) {
        approvalService.approve(employeeId, documentId);
        return ResponseEntity.ok(ApiResult.success(null));
    }

    /** 결재 반려 (현재 차례의 결재자, 사유 필수) */
    @PostMapping("/reject")
    public ResponseEntity<ApiResult<Void>> reject(
            @CurrentEmployeeId Long employeeId,
            @PathVariable Long documentId,
            @Valid @RequestBody RejectRequest request
    ) {
        approvalService.reject(employeeId, documentId, request.comment());
        return ResponseEntity.ok(ApiResult.success(null));
    }

    /** 결재 전결 (남은 결재선을 건너뛰고 최종 승인) */
    @PostMapping("/final-approve")
    public ResponseEntity<ApiResult<Void>> finalApprove(
            @CurrentEmployeeId Long employeeId,
            @PathVariable Long documentId
    ) {
        approvalService.finalApprove(employeeId, documentId);
        return ResponseEntity.ok(ApiResult.success(null));
    }

    /** 결재 취소 (기안자, 진행 중인 문서) */
    @PostMapping("/cancel")
    public ResponseEntity<ApiResult<Void>> cancel(
            @CurrentEmployeeId Long employeeId,
            @PathVariable Long documentId
    ) {
        approvalService.cancel(employeeId, documentId);
        return ResponseEntity.ok(ApiResult.success(null));
    }

    /** 재기안 (기안자, 반려된 문서. 결재선을 새로 지정하고 차수 +1) */
    @PostMapping("/resubmit")
    public ResponseEntity<ApiResult<Void>> resubmit(
            @CurrentEmployeeId Long employeeId,
            @PathVariable Long documentId,
            @Valid @RequestBody ResubmitRequest request
    ) {
        approvalService.resubmit(employeeId, documentId, request);
        return ResponseEntity.ok(ApiResult.success(null));
    }
}
