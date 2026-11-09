package com.ssafy.leaveservice.client.approval;

import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import java.util.UUID;

/** HTTP 구현이 없는 동안에는 설정을 켜도 신규 신청을 허용하지 않는다. */
public final class UnavailableApprovalClient implements ApprovalClient {
    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public ApprovalCreationResult create(ApprovalCreateCommand command) {
        throw new BusinessException(ErrorCode.APPROVAL_INTEGRATION_UNAVAILABLE);
    }

    @Override
    public ApprovalCreationResult findByRequestId(UUID approvalRequestId) {
        throw new BusinessException(ErrorCode.APPROVAL_INTEGRATION_UNAVAILABLE);
    }
}
