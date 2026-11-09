package com.ssafy.leaveservice.client.approval.withdrawal;

import java.util.UUID;

/** 외부 URI·DTO·인증·멱등성 매핑은 실제 HTTP 구현체에서 담당한다. */
public interface ApprovalWithdrawalClient {
    boolean isAvailable();

    ApprovalWithdrawalResult withdraw(ApprovalWithdrawalCommand command);

    // TODO Approval 담당자와 기존 UUID의 최종 철회 결과 조회 계약을 확정한 후 구현한다.
    ApprovalWithdrawalResult findByRequestId(UUID withdrawalRequestId);
}
