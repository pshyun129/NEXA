package com.ssafy.attendanceservice.domain.attendance.calculator;

import java.time.OffsetDateTime;

/** 시간 구간 [startAt, endAt) */
public record TimeRange(
        OffsetDateTime startAt,     // 시작 시각 (포함)
        OffsetDateTime endAt        // 종료 시각 (제외)
) {
}
