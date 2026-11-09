package com.ssafy.leaveservice.domain.request.dto;

import java.util.UUID;

public record LeaveApprovalDecision(
        int eventVersion,
        String eventType,
        String formType,
        String sourceType,
        Long sourceId,
        Long documentId,
        UUID requestKey,
        String status
) {
}
