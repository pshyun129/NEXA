package com.ssafy.attendanceservice.domain.attendance.calculator;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

/** 승인된 초과근무 구간 [startAt, endAt). 이 안에서 일한 초과근로만 승인으로 본다 (분 단위) */
public record ApprovedOvertime(
        OffsetDateTime startAt,     // 시작 시각 (포함)
        OffsetDateTime endAt        // 종료 시각 (제외)
) {
    public ApprovedOvertime {
        startAt = startAt.truncatedTo(ChronoUnit.MINUTES);
        endAt = endAt.truncatedTo(ChronoUnit.MINUTES);
    }

    /** 근로 1분(분 단위로 자른 시각)이 이 구간 안인가 */
    boolean contains(OffsetDateTime minute) {
        return !minute.isBefore(startAt) && minute.isBefore(endAt);
    }
}
