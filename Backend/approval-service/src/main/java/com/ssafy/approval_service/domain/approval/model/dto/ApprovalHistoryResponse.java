package com.ssafy.approval_service.domain.approval.model.dto;

import com.ssafy.approval_service.domain.approval.model.entity.ApprovalHistory;
import com.ssafy.approval_service.domain.approval.model.entity.enums.HistoryAction;

import java.time.OffsetDateTime;

public record ApprovalHistoryResponse(
        Long historyId,             // 이력ID
        Integer round,              // 재기안 차수
        Long actorId,               // 행위자 사원ID
        HistoryAction action,       // 행위
        Long targetEmpId,           // 결재자 추가/제거 대상 사원ID
        String comment,             // 반려 사유
        OffsetDateTime occurredAt   // 발생일시
) {
    public static ApprovalHistoryResponse from(ApprovalHistory history) {
        return new ApprovalHistoryResponse(
                history.getHistoryId(),
                history.getRound(),
                history.getActorId(),
                history.getAction(),
                history.getTargetEmpId(),
                history.getComment(),
                history.getOccurredAt()
        );
    }
}
