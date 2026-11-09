package com.ssafy.attendanceservice.domain.attendance.dto;

import com.ssafy.attendanceservice.domain.attendance.entity.Holiday;

import java.time.LocalDate;

/** 휴가를 쓸 수 없는 날 (공휴일, 대체휴일) */
public record HolidayResponse(
        LocalDate holidayDate,  // 휴일 일자
        String name,            // 휴일명
        String type             // 휴일 구분 (LEGAL 법정 공휴일, COMPANY 회사 지정 휴일, SUBSTITUTE 대체휴일)
) {
    private static final String SUBSTITUTE = "SUBSTITUTE";

    public static HolidayResponse from(Holiday holiday) {
        return new HolidayResponse(holiday.getHolidayDate(), holiday.getName(), holiday.getType().name());
    }

    /** 승인된 휴일대체로 쉬는 날 */
    public static HolidayResponse substitute(LocalDate date) {
        return new HolidayResponse(date, "대체휴일", SUBSTITUTE);
    }
}
