package com.ssafy.leaveservice.domain.request.dto;

import com.ssafy.leaveservice.client.attendance.AttendanceRevertCommand;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequest;

public record AdminCancellationAttempt(
        AdminLeaveCancellationResponse response,
        AttendanceRevertCommand command,
        boolean callRequired
) {
    public static AdminCancellationAttempt from(LeaveRequest request, boolean callRequired) {
        AttendanceRevertCommand command = new AttendanceRevertCommand(
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
