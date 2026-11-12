package com.ssafy.leaveservice.client.approval;

import java.util.UUID;

/** 오케스트레이션용 내부 결과이며 외부 Approval Response DTO가 아니다. */
public record ApprovalCreationResult(UUID approvalRequestId, Outcome outcome, Long documentId, String reason) {
    public ApprovalCreationResult(UUID approvalRequestId, Outcome outcome, Long documentId) {
        this(approvalRequestId, outcome, documentId, null);
    }

    public enum Outcome {
        SUBMITTED_CONFIRMED,
        NOT_CREATED_CONFIRMED,
        UNKNOWN,
        // 조회 404는 부재 확정이 아니다. 멱등 생성 재개 가능 여부만 호출부가 판단한다.
        LOOKUP_NOT_FOUND,
        CONFLICT,
        DRAFT_CREATED
    }
}
