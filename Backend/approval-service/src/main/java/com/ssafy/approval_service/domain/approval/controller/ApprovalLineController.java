package com.ssafy.approval_service.domain.approval.controller;

import com.ssafy.approval_service.domain.approval.model.dto.ApprovalLineResponse;
import com.ssafy.approval_service.domain.approval.model.dto.ApproverOrderRequest;
import com.ssafy.approval_service.domain.approval.model.dto.ParticipantAddRequest;
import com.ssafy.approval_service.domain.approval.service.ApprovalLineService;
import com.ssafy.approval_service.domain.document.model.dto.ApprovalLineRequest;
import com.ssafy.approval_service.global.dto.ApiResult;
import com.ssafy.approval_service.global.security.SecurityConstants;
import com.ssafy.nexa.security.AuthUser;
import com.ssafy.nexa.security.CurrentEmployeeId;
import com.ssafy.nexa.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 문서 참여자 API (결재자/참조자/열람자). 문서 하위 리소스로 두고, 대상은 경로의 사원 ID로 지정한다.
 * 추가/제거/순서 변경은 기안자만 가능하며 검증은 서비스에서 한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping(SecurityConstants.DOCUMENT_BASE_PATH + "/{documentId}")
public class ApprovalLineController {

    private final ApprovalLineService approvalLineService;

    // ===== 결재자 =====

    /** 결재자 조회 (결재 순서대로) */
    @GetMapping("/approvers")
    public ResponseEntity<ApiResult<List<ApprovalLineResponse>>> getApprovers(
            @CurrentUser AuthUser requester,
            @PathVariable Long documentId
    ) {
        return ResponseEntity.ok(ApiResult.success(approvalLineService.getApprovers(documentId, requester)));
    }

    /** 결재자 추가 */
    @PostMapping("/approvers")
    public ResponseEntity<ApiResult<Void>> addApprover(
            @CurrentEmployeeId Long employeeId,
            @PathVariable Long documentId,
            @Valid @RequestBody ApprovalLineRequest request
    ) {
        approvalLineService.addApprover(employeeId, documentId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.success(null));
    }

    /** 결재자 제거 */
    @DeleteMapping("/approvers/{empId}")
    public ResponseEntity<ApiResult<Void>> removeApprover(
            @CurrentEmployeeId Long employeeId,
            @PathVariable Long documentId,
            @PathVariable Long empId
    ) {
        approvalLineService.removeApprover(employeeId, documentId, empId);
        return ResponseEntity.ok(ApiResult.success(null));
    }

    /** 결재자 순서 변경 (전체 순서를 한 번에 교체) */
    @PatchMapping("/approvers/order")
    public ResponseEntity<ApiResult<Void>> reorderApprovers(
            @CurrentEmployeeId Long employeeId,
            @PathVariable Long documentId,
            @Valid @RequestBody ApproverOrderRequest request
    ) {
        approvalLineService.reorderApprovers(employeeId, documentId, request.empIds());
        return ResponseEntity.ok(ApiResult.success(null));
    }

    // ===== 참조자 =====

    /** 참조자 조회 */
    @GetMapping("/referrers")
    public ResponseEntity<ApiResult<List<ApprovalLineResponse>>> getReferrers(
            @CurrentUser AuthUser requester,
            @PathVariable Long documentId
    ) {
        return ResponseEntity.ok(ApiResult.success(approvalLineService.getReferrers(documentId, requester)));
    }

    /** 참조자 추가 */
    @PostMapping("/referrers")
    public ResponseEntity<ApiResult<Void>> addReferrer(
            @CurrentEmployeeId Long employeeId,
            @PathVariable Long documentId,
            @Valid @RequestBody ParticipantAddRequest request
    ) {
        approvalLineService.addReferrer(employeeId, documentId, request.empId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.success(null));
    }

    /** 참조자 제거 */
    @DeleteMapping("/referrers/{empId}")
    public ResponseEntity<ApiResult<Void>> removeReferrer(
            @CurrentEmployeeId Long employeeId,
            @PathVariable Long documentId,
            @PathVariable Long empId
    ) {
        approvalLineService.removeReferrer(employeeId, documentId, empId);
        return ResponseEntity.ok(ApiResult.success(null));
    }

    // ===== 열람자 =====

    /** 열람자 조회 */
    @GetMapping("/viewers")
    public ResponseEntity<ApiResult<List<ApprovalLineResponse>>> getViewers(
            @CurrentUser AuthUser requester,
            @PathVariable Long documentId
    ) {
        return ResponseEntity.ok(ApiResult.success(approvalLineService.getViewers(documentId, requester)));
    }

    /** 열람자 추가 */
    @PostMapping("/viewers")
    public ResponseEntity<ApiResult<Void>> addViewer(
            @CurrentEmployeeId Long employeeId,
            @PathVariable Long documentId,
            @Valid @RequestBody ParticipantAddRequest request
    ) {
        approvalLineService.addViewer(employeeId, documentId, request.empId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.success(null));
    }

    /** 열람자 제거 */
    @DeleteMapping("/viewers/{empId}")
    public ResponseEntity<ApiResult<Void>> removeViewer(
            @CurrentEmployeeId Long employeeId,
            @PathVariable Long documentId,
            @PathVariable Long empId
    ) {
        approvalLineService.removeViewer(employeeId, documentId, empId);
        return ResponseEntity.ok(ApiResult.success(null));
    }
}
