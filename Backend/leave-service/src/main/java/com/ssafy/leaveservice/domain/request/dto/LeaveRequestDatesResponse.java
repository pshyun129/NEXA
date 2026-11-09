package com.ssafy.leaveservice.domain.request.dto;

import com.ssafy.leaveservice.domain.request.entity.LeaveRequestPortion;

import java.time.LocalDate;
import java.util.List;

public record LeaveRequestDatesResponse(
        Long requestId,
        Long employeeId,
        String leaveTypeName,
        boolean isPaid,
        List<LeaveDateItem> dates
) {
    public record LeaveDateItem(LocalDate leaveDate, LeaveRequestPortion portion) {
    }
}
