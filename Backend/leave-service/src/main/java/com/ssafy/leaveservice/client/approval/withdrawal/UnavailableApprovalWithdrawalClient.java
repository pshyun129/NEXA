package com.ssafy.leaveservice.client.approval.withdrawal;

import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;

import java.util.UUID;

public final class UnavailableApprovalWithdrawalClient implements ApprovalWithdrawalClient {
    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public ApprovalWithdrawalResult withdraw(ApprovalWithdrawalCommand command) {
        throw new BusinessException(ErrorCode.APPROVAL_WITHDRAWAL_UNAVAILABLE);
    }

    @Override
    public ApprovalWithdrawalResult findByRequestId(UUID withdrawalRequestId) {
        throw new BusinessException(ErrorCode.APPROVAL_WITHDRAWAL_UNAVAILABLE);
    }
}
