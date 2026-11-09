package com.ssafy.attendanceservice.domain.attendance.dto;

import com.ssafy.attendanceservice.domain.attendance.entity.enums.LeavePortion;

import java.time.LocalDate;
import java.util.List;

/** 승인된 휴가 한 건 (휴가 서비스 조회 결과를 근태에 저장할 형태로) */
public record ApprovedLeave(
        Long leaveReqId,            // 휴가 서비스 신청 ID
        Long empId,                 // 사원 ID
        String leaveTypeName,       // 휴가 유형명
        boolean isPaid,             // 유급 여부
        List<Day> dates             // 날짜별 휴가
) {

    public record Day(
            LocalDate leaveDate,    // 휴가 일자
            LeavePortion portion    // 휴가 구분
    ) {
    }
}
