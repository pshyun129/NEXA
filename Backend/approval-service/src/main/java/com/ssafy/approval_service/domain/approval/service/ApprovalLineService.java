package com.ssafy.approval_service.domain.approval.service;

import com.ssafy.approval_service.domain.approval.model.dto.ApprovalLineResponse;
import com.ssafy.approval_service.domain.document.model.dto.ApprovalLineRequest;
import com.ssafy.nexa.security.AuthUser;

import java.util.List;

/** 문서 참여자(결재자/참조자/열람자) 관리. 모두 approval_line에 role로 구분해 저장한다. 조회는 문서 조회 권한이 있어야 한다 */
public interface ApprovalLineService {

    /** 결재자 조회 (APPROVER/AGREEMENT, step 순) */
    List<ApprovalLineResponse> getApprovers(Long documentId, AuthUser requester);

    /** 결재자 추가 (기안자) */
    void addApprover(Long employeeId, Long documentId, ApprovalLineRequest request);

    /** 결재자 제거 (기안자) */
    void removeApprover(Long employeeId, Long documentId, Long approverId);

    /** 결재자 순서 변경 (기안자) */
    void reorderApprovers(Long employeeId, Long documentId, List<Long> approverIds);

    /** 참조자 조회 */
    List<ApprovalLineResponse> getReferrers(Long documentId, AuthUser requester);

    /** 참조자 추가 (기안자) */
    void addReferrer(Long employeeId, Long documentId, Long referrerId);

    /** 참조자 제거 (기안자) */
    void removeReferrer(Long employeeId, Long documentId, Long referrerId);

    /** 열람자 조회 */
    List<ApprovalLineResponse> getViewers(Long documentId, AuthUser requester);

    /** 열람자 추가 (기안자) */
    void addViewer(Long employeeId, Long documentId, Long viewerId);

    /** 열람자 제거 (기안자) */
    void removeViewer(Long employeeId, Long documentId, Long viewerId);
}
