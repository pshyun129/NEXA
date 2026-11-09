package com.ssafy.attendanceservice.domain.attendance.entity.enums;

import java.time.LocalTime;

/** 휴가 구분 (휴가 서비스 값과 같다). 반차 시각은 휴가 서비스와 맞춘 고정값 */
public enum LeavePortion {
    FULL(null, null, 480),                                      // 종일
    AM_HALF(LocalTime.of(9, 0), LocalTime.of(13, 0), 240),      // 오전 반차 (09~13)
    PM_HALF(LocalTime.of(14, 0), LocalTime.of(18, 0), 240);     // 오후 반차 (14~18)

    private final LocalTime startTime;
    private final LocalTime endTime;
    private final int minutes;

    LeavePortion(LocalTime startTime, LocalTime endTime, int minutes) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.minutes = minutes;
    }

    /** 반차 시작 시각. 종일이면 null */
    public LocalTime startTime() {
        return startTime;
    }

    /** 반차 종료 시각. 종일이면 null */
    public LocalTime endTime() {
        return endTime;
    }

    /** 휴가 시간(분). 하루 480분 기준 */
    public int minutes() {
        return minutes;
    }

    public boolean isHalf() {
        return this != FULL;
    }
}
