package com.ssafy.leaveservice.domain.request.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ssafy.leaveservice.domain.request.dto.LeaveApprovalDecision;

import java.time.OffsetDateTime;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
record ApprovalResultEvent(
        UUID eventId,
        int eventVersion,
        String eventType,
        OffsetDateTime occurredAt,
        Long documentId,
        String docNo,
        String formType,
        String sourceType,
        Long sourceId,
        UUID requestKey,
        String status
) {
    LeaveApprovalDecision toDecision() {
        return new LeaveApprovalDecision(
                eventVersion, eventType, formType, sourceType, sourceId, documentId, requestKey, status
        );
    }
}
