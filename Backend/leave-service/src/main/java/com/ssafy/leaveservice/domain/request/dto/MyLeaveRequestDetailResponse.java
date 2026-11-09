package com.ssafy.leaveservice.domain.request.dto;

import com.ssafy.leaveservice.domain.leavetype.entity.LeaveType;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequest;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestStatus;

import java.time.OffsetDateTime;
import java.util.List;

public record MyLeaveRequestDetailResponse(
        Long requestId,
        Long employeeId,
        Long policyVersionId,
        Long leaveTypeId,
        String leaveTypeName,
        Long approvalDocId,
        LeaveRequestStatus status,
        int totalMinutes,
        int totalDeductMinutes,
        String reason,
        OffsetDateTime submittedAt,
        OffsetDateTime cancelledAt,
        Long cancelledBy,
        String cancelReason,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<LeaveRequestDateResponse> dates
) {

    public static MyLeaveRequestDetailResponse from(LeaveRequest leaveRequest) {
        LeaveType leaveType = leaveRequest.getLeaveType();
        List<LeaveRequestDateResponse> dates = leaveRequest.getDates().stream()
                .map(LeaveRequestDateResponse::from)
                .toList();
        return new MyLeaveRequestDetailResponse(
                leaveRequest.getRequestId(),
                leaveRequest.getEmployeeId(),
                leaveRequest.getPolicyVersionId(),
                leaveType.getLeaveTypeId(),
                leaveType.getName(),
                leaveRequest.getApprovalDocId(),
                leaveRequest.getStatus(),
                leaveRequest.getTotalMinutes(),
                leaveRequest.getTotalDeductMinutes(),
                leaveRequest.getReason(),
                leaveRequest.getSubmittedAt(),
                leaveRequest.getCancelledAt(),
                leaveRequest.getCancelledBy(),
                leaveRequest.getCancelReason(),
                leaveRequest.getCreatedAt(),
                leaveRequest.getUpdatedAt(),
                dates
        );
    }
}
