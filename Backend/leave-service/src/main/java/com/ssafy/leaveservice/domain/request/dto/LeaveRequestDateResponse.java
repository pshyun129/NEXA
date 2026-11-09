package com.ssafy.leaveservice.domain.request.dto;

import com.ssafy.leaveservice.domain.request.entity.LeaveRequestDate;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestPortion;

import java.time.OffsetDateTime;

public record LeaveRequestDateResponse(
        Long requestDateId,
        OffsetDateTime leaveDate,
        LeaveRequestPortion portion,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        Integer scheduledWorkMinutes,
        int requestedMinutes,
        int deductMinutes
) {

    public static LeaveRequestDateResponse from(LeaveRequestDate requestDate) {
        return new LeaveRequestDateResponse(
                requestDate.getRequestDateId(),
                requestDate.getLeaveDate(),
                requestDate.getPortion(),
                requestDate.getStartAt(),
                requestDate.getEndAt(),
                requestDate.getScheduledWorkMinutes(),
                requestDate.getRequestedMinutes(),
                requestDate.getDeductMinutes()
        );
    }
}
