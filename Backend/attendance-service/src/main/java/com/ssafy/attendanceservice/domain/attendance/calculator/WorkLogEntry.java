package com.ssafy.attendanceservice.domain.attendance.calculator;

import java.time.OffsetDateTime;

/** 계산용 상태 로그 한 줄. 다음 로그 시작(또는 퇴근)까지가 이 상태의 구간이다. */
public record WorkLogEntry(
        OffsetDateTime startAt,     // 상태 시작 시각
        boolean countAsWork         // 근로시간 포함 여부
) {
}