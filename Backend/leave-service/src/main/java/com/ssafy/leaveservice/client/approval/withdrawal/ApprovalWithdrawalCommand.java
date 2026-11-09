package com.ssafy.leaveservice.client.approval.withdrawal;

import java.util.UUID;

/** 오케스트레이션 내부 모델이며 외부 Approval 요청 JSON이 아니다. */
public record ApprovalWithdrawalCommand(
        Long requestId,
        Long employeeId,
        Long documentId,
        UUID withdrawalRequestId,
        Long administratorId,
        String reason
) {
    public String idempotencyKey() {
        return "leave:admin-cancel:request:" + requestId;
    }
}
