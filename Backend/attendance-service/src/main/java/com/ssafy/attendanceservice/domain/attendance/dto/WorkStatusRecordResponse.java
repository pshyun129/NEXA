package com.ssafy.attendanceservice.domain.attendance.dto;

import com.ssafy.attendanceservice.domain.attendance.entity.WorkStatusLog;
import com.ssafy.attendanceservice.global.util.DateTimeUtils;

import java.time.OffsetDateTime;

/** 근무 상태 기록 */
public record WorkStatusRecordResponse(
        String statusCode,          // 상태 코드
        String statusName,          // 상태명
        Boolean countAsWork,        // 근로시간 포함 여부
        OffsetDateTime startAt      // 상태 시작 시각
) {
    public static WorkStatusRecordResponse from(WorkStatusLog log) {
        return new WorkStatusRecordResponse(
                log.getWorkStatusCode().getStatusCode(),
                log.getWorkStatusCode().getName(),
                log.getWorkStatusCode().getCountAsWork(),
                DateTimeUtils.toSeoul(log.getStartAt())
        );
    }
}