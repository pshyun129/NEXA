package com.ssafy.attendanceservice.domain.attendance.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ssafy.attendanceservice.domain.attendance.dto.ApprovedLeave;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.LeavePortion;

import java.time.LocalDate;
import java.util.List;

/** 휴가 서비스의 휴가 신청 날짜 (GET /api/v1/leaves/internal/requests/{requestId}/dates) */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LeaveRequestDates(
        Long requestId,         // 휴가 신청 ID
        Long employeeId,        // 신청한 사원
        String leaveTypeName,   // 휴가 유형명
        boolean isPaid,         // 유급 여부
        List<LeaveDate> dates   // 날짜별 휴가 (서울 기준 날짜)
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LeaveDate(
            LocalDate leaveDate,    // 휴가 일자
            LeavePortion portion    // FULL, AM_HALF, PM_HALF
    ) {
    }

    /** 근태에 저장할 승인된 휴가 */
    public ApprovedLeave toApprovedLeave() {
        return new ApprovedLeave(requestId, employeeId, leaveTypeName, isPaid,
                dates.stream().map(date -> new ApprovedLeave.Day(date.leaveDate(), date.portion())).toList());
    }
}
