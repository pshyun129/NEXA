package com.ssafy.attendanceservice.domain.attendance.dto;

import com.ssafy.attendanceservice.domain.attendance.entity.WorkStatusLog;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.WorkStatusSource;
import com.ssafy.attendanceservice.global.util.DateTimeUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 근무상태 변경 이력 한 건 */
public record WorkStatusHistoryResponse(
        Long logId,                 // 로그 ID
        LocalDate workDate,         // 근무일
        String statusCode,          // 상태 코드
        String statusName,          // 상태명
        OffsetDateTime startAt,     // 상태 시작 시각
        WorkStatusSource source,    // 생성 주체 (SELF, ADMIN, APPROVAL)
        Long createdBy,             // 기록을 만든 사람
        String reason,              // 보정, 결재 사유
        Boolean isDeleted,          // 삭제 여부
        OffsetDateTime createdAt    // 기록 저장 시각
) {
    public static WorkStatusHistoryResponse from(WorkStatusLog log) {
        return new WorkStatusHistoryResponse(
                log.getLogId(),
                log.getWorkDate(),
                log.getWorkStatusCode().getStatusCode(),
                log.getWorkStatusCode().getName(),
                DateTimeUtils.toSeoul(log.getStartAt()),
                log.getSource(),
                log.getCreatedBy(),
                log.getReason(),
                log.getIsDeleted(),
                DateTimeUtils.toSeoul(log.getCreatedAt())
        );
    }
}
