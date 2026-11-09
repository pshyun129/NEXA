package com.ssafy.leaveservice.domain.request.dto;

import com.ssafy.leaveservice.domain.request.entity.LeaveRequest;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestStatus;

import java.time.OffsetDateTime;

public record AdminLeaveRequestListResponse(
        Long requestId,
        Long employeeId,
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

    public static AdminLeaveRequestListResponse from(LeaveRequest leaveRequest) {
        MyLeaveRequestListResponse request = MyLeaveRequestListResponse.from(leaveRequest);
        return new AdminLeaveRequestListResponse(
                request.requestId(),
                leaveRequest.getEmployeeId(),
                request.policyVersionId(),
                request.leaveTypeId(),
                request.leaveTypeName(),
                request.status(),
                request.totalMinutes(),
                request.totalDeductMinutes(),
                request.submittedAt(),
                request.createdAt(),
                request.updatedAt()
        );
    }
}
