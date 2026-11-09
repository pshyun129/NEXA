package com.ssafy.attendanceservice.domain.attendance.dto;

import com.ssafy.attendanceservice.domain.attendance.entity.AttendanceRequest;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AttendanceRequestStatus;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AttendanceRequestType;
import com.ssafy.attendanceservice.global.util.DateTimeUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 근태 신청 이력 한 건 (상세 항목 제외) */
public record AttendanceRequestSummaryResponse(
        Long attReqId,                      // 신청 ID
        AttendanceRequestType reqType,      // 신청 유형
        LocalDate targetDate,               // 대상일
        LocalDate substDate,                // 대체 휴일
        String reason,                      // 신청 사유
        AttendanceRequestStatus status,     // 결재 상태
        OffsetDateTime createdAt,           // 상신 일시
        OffsetDateTime completedAt          // 결재 완료 일시
) {
    public static AttendanceRequestSummaryResponse from(AttendanceRequest request) {
        return new AttendanceRequestSummaryResponse(
                request.getAttReqId(),
                request.getReqType(),
                request.getTargetDate(),
                request.getSubstDate(),
                request.getReason(),
                request.getStatus(),
                DateTimeUtils.toSeoul(request.getCreatedAt()),
                DateTimeUtils.toSeoul(request.getCompletedAt())
        );
    }
}
