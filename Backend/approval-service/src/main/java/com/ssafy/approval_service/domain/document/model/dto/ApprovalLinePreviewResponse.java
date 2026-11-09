package com.ssafy.approval_service.domain.document.model.dto;

import com.ssafy.approval_service.domain.approval.policy.ApprovalLevel;
import com.ssafy.approval_service.domain.approval.policy.GeneratedApprovalLine;
import com.ssafy.approval_service.domain.document.model.entity.enums.FormType;

import java.util.List;

/**
 * 기본 결재선 미리보기. 결재 화면이 기안 전에 보여주고, 사용자가 고쳐서 approvalLines 로 보내거나 생략할 수 있다.
 * selfApproval 이 true 면 결재자 없이 상신 즉시 승인된다 (대표이사 기안)
 */
public record ApprovalLinePreviewResponse(
        FormType formType,                      // 문서양식
        ApprovalLevel approvalLevel,            // 규정상 전결 단계
        boolean selfApproval,                   // 자가결재 여부
        List<ApprovalLineRequest> approvalLines // 결재자 (step 순)
) {
    public static ApprovalLinePreviewResponse of(FormType formType, ApprovalLevel level, GeneratedApprovalLine line) {
        return new ApprovalLinePreviewResponse(formType, level, line.selfApproval(), line.lines());
    }
}
