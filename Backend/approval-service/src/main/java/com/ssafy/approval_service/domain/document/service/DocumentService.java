package com.ssafy.approval_service.domain.document.service;

import com.ssafy.approval_service.domain.approval.policy.ApprovalAttributes;
import com.ssafy.approval_service.domain.document.model.dto.ApprovalLinePreviewResponse;
import com.ssafy.approval_service.domain.document.model.dto.DocumentCountResponse;
import com.ssafy.approval_service.domain.document.model.dto.DocumentCreateRequest;
import com.ssafy.approval_service.domain.document.model.dto.DocumentInfoResponse;
import com.ssafy.approval_service.domain.document.model.dto.DocumentListResponse;
import com.ssafy.approval_service.domain.document.model.entity.Document;
import com.ssafy.approval_service.domain.document.model.entity.enums.FormType;
import com.ssafy.nexa.security.AuthUser;

import java.util.UUID;

public interface DocumentService {

    /** 결재상태/문서양식/문서함으로 목록 조회. 모두 null 허용. 요청자가 조회할 수 있는 문서만 */
    /** page·size 가 둘 다 null 이면 전체, 아니면 page(0부터)·size(1~100) 로 나눈다 */
    DocumentListResponse findByStatusAndType(String approvalStatus, String formType, String box,
                                             Integer page, Integer size, AuthUser requester);

    /** findByStatusAndType 과 같은 조건의 건수 */
    DocumentCountResponse countByStatusAndType(String approvalStatus, String formType, String box, AuthUser requester);

    /**
     * 문서 생성 + 상신. 결재선 저장과 SUBMIT 이력 기록까지 한 트랜잭션.
     * 같은 요청 키로 이미 만든 문서가 있으면 새로 만들지 않고 그 문서를 돌려준다 (created=false)
     */
    DocumentCreateResult create(DocumentCreateRequest request, Long drafterId);

    /** 기본 결재선 미리보기. 위임전결 규정과 조직도로 만든다 (저장하지 않음) */
    ApprovalLinePreviewResponse previewApprovalLine(FormType formType, ApprovalAttributes attributes, Long drafterId);

    /** 문서 상세 (문서 + 결재선 + 이력) */
    DocumentInfoResponse findById(Long documentId, AuthUser requester);

    /** 생성 요청 키로 문서 상세 조회. 없거나 조회 권한이 없으면 404 */
    DocumentInfoResponse findByRequestKey(UUID requestKey, AuthUser requester);

    /** 변경용 엔티티 조회. 문서 행을 잠가 동시 변경을 막는다. 없으면 404 */
    Document getDocumentForUpdate(Long documentId);

    /** 조회 권한까지 확인한 문서. 없거나 권한이 없으면 404 */
    Document getReadableDocument(Long documentId, AuthUser requester);
}
