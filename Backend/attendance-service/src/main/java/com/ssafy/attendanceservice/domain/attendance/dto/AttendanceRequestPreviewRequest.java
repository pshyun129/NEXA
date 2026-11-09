package com.ssafy.attendanceservice.domain.attendance.dto;

import com.ssafy.attendanceservice.domain.attendance.entity.enums.AttendanceRequestType;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.RequestItemAction;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 근태 신청 검증 요청. 유형별로 쓰는 필드가 다르며, 유형별 필수 여부는 서비스에서 검증한다. */
public record AttendanceRequestPreviewRequest(
        @NotNull AttendanceRequestType reqType,     // 신청 유형
        @NotNull LocalDate targetDate,              // 대상일
        LocalDate substDate,                        // 대체 휴일 (HOLIDAY_SUBST)
        List<Item> items                            // 신청 상세 (TIME_FIX, OVERTIME)
) {

    public record Item(
            RequestItemAction action,       // 처리 구분 (TIME_FIX)
            Long targetLogId,               // 수정/삭제 대상 로그 (TIME_FIX의 MODIFY, DELETE)
            String statusCode,              // 근무 상태
            OffsetDateTime startAt,         // 시작 시각
            OffsetDateTime endAt            // 종료 시각 (OVERTIME)
    ) {
    }

    /** items가 없으면 빈 목록 */
    public List<Item> itemsOrEmpty() {
        return items == null ? List.of() : items;
    }
}