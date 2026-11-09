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
        CONFLICT,
        DRAFT_CREATED
    }
}
