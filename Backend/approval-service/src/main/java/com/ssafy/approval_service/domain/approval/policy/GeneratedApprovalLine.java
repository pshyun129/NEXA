package com.ssafy.approval_service.domain.approval.policy;

import com.ssafy.approval_service.domain.approval.model.entity.ApprovalLine;
import com.ssafy.approval_service.domain.document.model.dto.ApprovalLineRequest;
import com.ssafy.approval_service.domain.document.model.entity.Document;

import java.util.List;

/**
 * 조직도로 만든 결재선.
 *
 * @param lines        결재자(APPROVER)만, step 1부터 아래 단계 → 위 단계 순
 * @param selfApproval 기안자가 최상위 부서장(대표이사)이라 결재자가 없음 → 상신 즉시 승인 (자가결재)
 */
public record GeneratedApprovalLine(List<ApprovalLineRequest> lines, boolean selfApproval) {

    /** 문서에 붙일 결재선 엔티티. 규정으로 정한 결재자이므로 required (제외·순서 변경·전결 건너뛰기 불가) */
    public List<ApprovalLine> toEntities(Document document) {
        return lines.stream()
                .map(line -> ApprovalLine.builder()
                        .document(document)
                        .empId(line.empId())
                        .step(line.step())
                        .role(line.role())
                        .required(true)
                        .build())
                .toList();
    }
}
