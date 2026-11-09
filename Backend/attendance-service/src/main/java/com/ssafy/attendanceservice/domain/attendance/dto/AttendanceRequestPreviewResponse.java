package com.ssafy.attendanceservice.domain.attendance.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AttendanceRequestType;

import java.time.LocalDate;

/** 근태 신청 검증 결과. 신청 유형에 해당하는 항목만 채워진다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AttendanceRequestPreviewResponse(
        AttendanceRequestType reqType,      // 신청 유형
        LocalDate targetDate,               // 대상일
        TimeFix timeFix,                    // TIME_FIX 결과
        Overtime overtime,                  // OVERTIME 결과
        HolidaySubst holidaySubst           // HOLIDAY_SUBST 결과
) {

    /** 근무시간 수정: 수정 전/후 근로시간 */
    public record TimeFix(
            WorkTimeResponse before,    // 신청일 근로시간
            WorkTimeResponse after,     // 수정 후 근로시간
            int weeklyTotalMin,         // 주간 총 근로시간 (수정 후)
            int weeklyMaxMin            // 주 최대 근로시간
    ) {
    }

    /** 초과근무: 신청 시간과 주 52시간 한도 */
    public record Overtime(
            int requestedMin,           // 신청 시간
            int weeklyTotalMin,         // 주간 근로시간 (신청 포함)
            int weeklyMaxMin,           // 주 최대 근로시간
            int remainMin               // 남은 한도
    ) {
    }

    /** 휴일대체: 근무한 휴일과 대신 쉴 날 */
    public record HolidaySubst(
            LocalDate targetDate,       // 근무한 휴일
            LocalDate substDate         // 대신 쉴 날
    ) {
    }

    public static AttendanceRequestPreviewResponse ofTimeFix(LocalDate targetDate, TimeFix timeFix) {
        return new AttendanceRequestPreviewResponse(AttendanceRequestType.TIME_FIX, targetDate, timeFix, null, null);
    }

    public static AttendanceRequestPreviewResponse ofOvertime(LocalDate targetDate, Overtime overtime) {
        return new AttendanceRequestPreviewResponse(AttendanceRequestType.OVERTIME, targetDate, null, overtime, null);
    }

    public static AttendanceRequestPreviewResponse ofHolidaySubst(LocalDate targetDate, HolidaySubst holidaySubst) {
        return new AttendanceRequestPreviewResponse(AttendanceRequestType.HOLIDAY_SUBST, targetDate, null, null, holidaySubst);
    }
}