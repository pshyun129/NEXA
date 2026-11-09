package com.ssafy.leaveservice.client.approval;

import com.ssafy.leaveservice.domain.request.dto.LeaveRequestDateResponse;
import com.ssafy.leaveservice.domain.request.dto.LeaveSubmissionResult;
import com.ssafy.leaveservice.domain.request.dto.MyLeaveRequestDetailResponse;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestPortion;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** T1에 저장된 업무 스냅샷. 외부 Request DTO/JSON은 구현체 안에서 별도로 매핑한다. */
public record ApprovalCreateCommand(
        Long requestId,
        Long employeeId,
        UUID approvalRequestId,
        Long policyVersionId,
        Long leaveTypeId,
        String reason,
        int totalMinutes,
        int totalDeductMinutes,
        List<LeaveDate> dates
) {
    public ApprovalCreateCommand {
        dates = List.copyOf(dates);
    }

    public static ApprovalCreateCommand from(
            LeaveSubmissionResult submission,
            MyLeaveRequestDetailResponse request
    ) {
        List<LeaveDate> dates = request.dates().stream().map(LeaveDate::from).toList();
        return new ApprovalCreateCommand(
                request.requestId(),
                request.employeeId(),
                submission.approvalRequestId(),
                request.policyVersionId(),
                request.leaveTypeId(),
                request.reason(),
                request.totalMinutes(),
                request.totalDeductMinutes(),
                dates
        );
    }

    public record LeaveDate(
            Long requestDateId,
            OffsetDateTime leaveDate,
            LeaveRequestPortion portion,
            int requestedMinutes,
            int deductMinutes
    ) {
        private static LeaveDate from(LeaveRequestDateResponse date) {
            return new LeaveDate(date.requestDateId(), date.leaveDate(), date.portion(), date.requestedMinutes(), date.deductMinutes());
        }
    }
}
