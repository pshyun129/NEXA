package com.ssafy.leaveservice.client.approval;

import java.util.UUID;

/** 외부 JSON/인증/멱등 키/결과 조회 경로의 매핑 책임은 향후 구현체에 둔다. */
public interface ApprovalClient {
    boolean isAvailable();

    ApprovalCreationResult create(ApprovalCreateCommand command);

    // TODO: Approval의 UUID 결과 조회 계약을 확정한 뒤 HTTP 구현체에서 연결한다.
    ApprovalCreationResult findByRequestId(UUID approvalRequestId);

    default boolean supportsRecovery() {
        return false;
    }

    default boolean supportsIdempotentResume() {
        return false;
    }

    default ApprovalCreationResult resume(ApprovalCreateCommand command) {
        return new ApprovalCreationResult(command.approvalRequestId(), ApprovalCreationResult.Outcome.UNKNOWN,
                null, "APPROVAL_RESUME_UNSUPPORTED");
    }

    default ApprovalCreationResult recover(com.ssafy.leaveservice.domain.request.dto.LeaveSubmissionResult request) {
        return findByRequestId(request.approvalRequestId());
    }
}
