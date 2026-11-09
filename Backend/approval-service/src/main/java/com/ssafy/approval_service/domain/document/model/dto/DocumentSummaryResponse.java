package com.ssafy.approval_service.domain.document.model.dto;

import com.ssafy.approval_service.domain.approval.model.ApprovalProgress;
import com.ssafy.approval_service.domain.document.model.entity.Document;
import com.ssafy.approval_service.domain.document.model.entity.enums.DocumentStatus;
import com.ssafy.approval_service.domain.document.model.entity.enums.FormType;
import com.ssafy.approval_service.domain.document.model.entity.enums.SourceType;

import java.time.OffsetDateTime;
import java.util.List;

/** 문서 목록의 한 건. 진행 상황(현재 결재자·단계·내 차례)은 ApprovalProgress 규칙으로 계산한다 */
public record DocumentSummaryResponse(
        Long documentId,                // 문서ID
        String docNo,                   // 문서번호
        FormType formType,              // 문서양식
        String title,                   // 제목
        DocumentStatus status,          // 결재상태
        Long drafterId,                 // 기안자 사원ID
        OffsetDateTime draftedAt,       // 상신일시
        OffsetDateTime completedAt,     // 완료일시
        SourceType sourceType,          // 출처 서비스 (DIRECT 면 직접 기안)
        Long sourceId,                  // 출처 서비스의 원본 신청 ID (DIRECT 면 null)
        String sourceLabel,             // 출처 표시 이름 (예: 워크플로우 이름). 없으면 null
        List<Long> currentApproverIds,  // 현재 차례인 사원 ID. 진행 중이 아니면 []
        Integer currentStep,            // 현재 차례가 결재자 중 몇 번째인지 (1부터). 진행 중이 아니면 null
        int totalSteps,                 // 결재자(결재·합의) 수
        boolean myTurn                  // 요청자가 현재 차례인지
) {
    public static DocumentSummaryResponse of(Document document, ApprovalProgress progress, Long requesterId) {
        return new DocumentSummaryResponse(
                document.getDocId(),
                document.getDocNo(),
                document.getFormType(),
                document.getTitle(),
                document.getStatus(),
                document.getDrafterId(),
                document.getDraftedAt(),
                document.getCompletedAt(),
                document.getSourceType(),
                document.getSourceId(),
                document.getSourceLabel(),
                progress.currentApproverIds(),
                progress.currentStep(),
                progress.totalSteps(),
                progress.isTurnOf(requesterId)
        );
    }
}
