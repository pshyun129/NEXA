package com.ssafy.attendanceservice.domain.attendance.entity.enums;

public enum AnomalyType {
    LATE,               // 지각
    EARLY_LEAVE,        // 조퇴
    MISSING_CLOCK,      // 출퇴근 누락
    UNDER_STD_HOURS,    // 소정근로 미달
    SHORT_BREAK,        // 휴게시간 부족 (판정 보류)
    WORK_ON_LEAVE,      // 휴가 중 출근 (판정 보류)
    UNAPPROVED_OT       // 미승인 초과근로
}