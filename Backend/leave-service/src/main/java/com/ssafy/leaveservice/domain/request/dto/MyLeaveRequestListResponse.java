package com.ssafy.leaveservice.domain.request.dto;

import com.ssafy.leaveservice.domain.leavetype.entity.LeaveType;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequest;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestStatus;

import java.time.OffsetDateTime;

public record MyLeaveRequestListResponse(
        Long requestId,
        Long policyVersionId,
        Long leaveTypeId,
        String leaveTypeName,
        LeaveRequestStatus status,
        int totalMinutes,
        int totalDeductMinutes,
        OffsetDateTime submittedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static MyLeaveRequestListResponse from(LeaveRequest leaveRequest) {
        LeaveType leaveType = leaveRequest.getLeaveType();
        return new MyLeaveRequestListResponse(
                leaveRequest.getRequestId(),
                leaveRequest.getPolicyVersionId(),
                leaveType.getLeaveTypeId(),
                leaveType.getName(),
                leaveRequest.getStatus(),
                leaveRequest.getTotalMinutes(),
                leaveRequest.getTotalDeductMinutes(),
                leaveRequest.getSubmittedAt(),
                leaveRequest.getCreatedAt(),
                leaveRequest.getUpdatedAt()
        );
    }
}
