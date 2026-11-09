package com.ssafy.leaveservice.domain.request.dto;

import com.ssafy.leaveservice.client.approval.withdrawal.ApprovalWithdrawalCommand;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequest;

public record AdminCancellationAttempt(
        AdminLeaveCancellationResponse response,
        ApprovalWithdrawalCommand command,
        boolean callRequired
) {
    public static AdminCancellationAttempt from(LeaveRequest request, boolean callRequired) {
        ApprovalWithdrawalCommand command = new ApprovalWithdrawalCommand(
                request.getRequestId(),
                request.getEmployeeId(),
                request.getApprovalDocId(),
                request.getAdminCancelRequestId(),
                request.getAdminCancelRequestedBy(),
                request.getAdminCancelReason()
        );
        return new AdminCancellationAttempt(AdminLeaveCancellationResponse.from(request), command, callRequired);
    }
}
