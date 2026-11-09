package com.ssafy.attendanceservice.domain.attendance.entity.enums;

public enum AttendanceRequestStatus {
    PENDING,    // 결재 대기
    APPROVED,   // 승인
    REJECTED,   // 반려
    CANCELED    // 회수
}
