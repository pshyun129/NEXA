package com.ssafy.leaveservice.client.approval.withdrawal;

import java.util.UUID;

/** 단순 접수와 최종 철회를 구분하는 내부 결과이며 외부 응답 DTO가 아니다. */
public record ApprovalWithdrawalResult(UUID withdrawalRequestId, Long documentId, Outcome outcome) {
    public enum Outcome {
        WITHDRAWN_CONFIRMED,
        NOT_WITHDRAWN_CONFIRMED,
        ACCEPTED,
        UNKNOWN
    }
}
