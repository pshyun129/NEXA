package com.ssafy.leaveservice.domain.request.dto;

import com.ssafy.leaveservice.domain.request.entity.*;
import java.util.UUID;

public record LeaveSubmissionResult(
        Long requestId,
        Long employeeId,
        UUID approvalRequestId,
        LeaveRequestStatus status,
        ApprovalCreationStatus approvalCreationStatus,
        Long approvalDocId
) {
    public static LeaveSubmissionResult from(LeaveRequest request) {
        return new LeaveSubmissionResult(request.getRequestId(), request.getEmployeeId(), request.getApprovalRequestId(),
                request.getStatus(), request.getApprovalCreationStatus(), request.getApprovalDocId());
    }
}
