package com.ssafy.leaveservice.domain.department.dto;

import com.ssafy.leaveservice.domain.request.entity.LeaveRequest;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestDate;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestPortion;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestStatus;
import com.ssafy.leaveservice.domain.request.service.PreviewGrantAllocator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public record DepartmentLeaveScheduleResponse(
        Long requestId,
        Long employeeId,
        LeaveRequestStatus status,
        List<DateItem> dates
) {
    public static DepartmentLeaveScheduleResponse from(LeaveRequest request) {
        List<DateItem> dates = new ArrayList<>();
        for (LeaveRequestDate date : request.getDates()) {
            LocalDate leaveDate = date.getLeaveDate().atZoneSameInstant(PreviewGrantAllocator.SEOUL).toLocalDate();
            dates.add(new DateItem(leaveDate, date.getPortion()));
        }
        return new DepartmentLeaveScheduleResponse(
                request.getRequestId(),
                request.getEmployeeId(),
                request.getStatus(),
                dates
        );
    }

    public record DateItem(LocalDate leaveDate, LeaveRequestPortion portion) {}
}
