package com.ssafy.attendanceservice.domain.attendance.dto;

import com.ssafy.attendanceservice.domain.attendance.entity.EmployeeLeave;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.LeavePortion;

/** 그날 휴가 */
public record LeaveResponse(
        String leaveTypeName,   // 휴가 유형명 (연차, 병가 등)
        LeavePortion portion,   // 휴가 구분 (FULL, AM_HALF, PM_HALF)
        boolean isPaid          // 유급 여부
) {
    public static LeaveResponse from(EmployeeLeave leave) {
        return new LeaveResponse(leave.getLeaveTypeName(), leave.getPortion(), leave.getIsPaid());
    }
}
