package com.ssafy.approval_service.domain.document.controller;

import com.ssafy.approval_service.domain.approval.policy.ApprovalAttributes;
import com.ssafy.approval_service.domain.document.model.dto.ApprovalLinePreviewResponse;
import com.ssafy.approval_service.domain.document.model.dto.DocumentCountResponse;
import com.ssafy.approval_service.domain.document.model.dto.DocumentCreateRequest;
import com.ssafy.approval_service.domain.document.model.dto.DocumentCreateResponse;
import com.ssafy.approval_service.domain.document.model.dto.DocumentInfoResponse;
import com.ssafy.approval_service.domain.document.model.dto.DocumentListResponse;
import com.ssafy.approval_service.domain.document.model.entity.enums.FormType;
import com.ssafy.approval_service.domain.document.service.DocumentCreateResult;
import com.ssafy.approval_service.domain.document.service.DocumentService;
import com.ssafy.approval_service.global.dto.ApiResult;
import com.ssafy.approval_service.global.security.SecurityConstants;
import com.ssafy.nexa.security.AuthUser;
import com.ssafy.nexa.security.CurrentEmployeeId;
import com.ssafy.nexa.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(SecurityConstants.DOCUMENT_BASE_PATH)
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    /**
     * 문서 목록 조회
     * approvalStatus = 결재상태 (INPROGRESS/APPROVED/REJECTED/CANCEL)
     * formType = 문서양식
     * box = 문서함 (DRAFTED: 기안함, PENDING: 결재 대기함, REFERENCE: 참조함, COMPLETED: 처리함). 없으면 조회할 수 있는 문서 전체
     * page, size = 페이지(0부터)·크기(1~100, 기본 20). 둘 다 없으면 전체
     */
    @GetMapping
    public ResponseEntity<ApiResult<DocumentListResponse>> getDocumentList(
            @CurrentUser AuthUser requester,
            @RequestParam(required = false) String approvalStatus,
            @RequestParam(required = false) String formType,
            @RequestParam(required = false) String box,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        DocumentListResponse response = documentService.findByStatusAndType(
                approvalStatus, formType, box, page, size, requester);
        return ResponseEntity.ok(ApiResult.success(response));
    }

    /** 문서 건수. 파라미터는 목록과 같다 (예: box=PENDING 결재 대기 카운터) */
    @GetMapping("/count")
    public ResponseEntity<ApiResult<DocumentCountResponse>> countDocuments(
            @CurrentUser AuthUser requester,
            @RequestParam(required = false) String approvalStatus,
            @RequestParam(required = false) String formType,
            @RequestParam(required = false) String box
    ) {
        return ResponseEntity.ok(ApiResult.success(
                documentService.countByStatusAndType(approvalStatus, formType, box, requester)));
    }

    /**
     * 기본 결재선 미리보기. 요청자를 기안자로 보고 위임전결 규정과 조직도로 만든다 (저장하지 않음).
     * 신청 값은 쿼리로: days, overtimeMinutes, monthlyOvertimeMinutes, overseas, amount (모두 선택)
     */
    @GetMapping("/approval-line-preview")
    public ResponseEntity<ApiResult<ApprovalLinePreviewResponse>> previewApprovalLine(
            @CurrentEmployeeId Long drafterId,
            @RequestParam FormType formType,
            @Valid @ModelAttribute ApprovalAttributes attributes
    ) {
        return ResponseEntity.ok(ApiResult.success(
                documentService.previewApprovalLine(formType, attributes, drafterId)));
    }

    /** 문서 상세 (문서 + 결재선 + 이력) */
    @GetMapping("/{documentId}")
    public ResponseEntity<ApiResult<DocumentInfoResponse>> getDocumentInfo(
            @CurrentUser AuthUser requester,
            @PathVariable Long documentId
    ) {
        DocumentInfoResponse response = documentService.findById(documentId, requester);
        return ResponseEntity.ok(ApiResult.success(response));
    }

    /** 문서 생성 + 상신. 기안자는 access 토큰의 sub(사원 ID)로 식별 */
    /**
     * 문서 생성 + 상신. 새로 만들면 201, 같은 요청 키로 이미 만든 문서를 돌려주면 200
     */
    @PostMapping
    public ResponseEntity<ApiResult<DocumentCreateResponse>> createDocument(
            @CurrentEmployeeId Long drafterId,
            @Valid @RequestBody DocumentCreateRequest request
    ) {
        DocumentCreateResult result = documentService.create(request, drafterId);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResult.success(result.response()));
    }

    /**
     * 생성 요청 키로 문서 조회. 원본 서비스가 생성 결과를 모르거나 결과 이벤트를 놓쳤을 때 쓴다.
     * 404 면 이 키로 만든 문서가 없다 (또는 조회 권한 없음)
     */
    @GetMapping("/by-request/{requestKey}")
    public ResponseEntity<ApiResult<DocumentInfoResponse>> getDocumentByRequestKey(
            @CurrentUser AuthUser requester,
            @PathVariable UUID requestKey
    ) {
        return ResponseEntity.ok(ApiResult.success(documentService.findByRequestKey(requestKey, requester)));
    }
}
