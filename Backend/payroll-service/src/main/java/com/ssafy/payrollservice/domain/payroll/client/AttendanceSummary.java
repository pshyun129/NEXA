package com.ssafy.payrollservice.domain.payroll.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 근태 서비스 월별 집계 (GET /api/v1/attn/summary). 급여에 쓰는 필드만 받는다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AttendanceSummary(
        Days days,          // 일수
        WorkTime workTime,  // 근로시간 합계 (분)
        Leave leave         // 휴가 합계 (휴가 반영 전 근태 서비스면 null)
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Leave(
            int leaveDays,          // 근무일 중 종일 휴가일
            int leaveMin,           // 휴가 시간 (종일 480, 반차 240)
            int unpaidLeaveMin      // 그중 무급휴가 시간
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Days(
            int workDays,           // 근무일 수 (휴일 제외)
            int attendedDays,       // 근무일 중 출근한 날
            int holidayWorkedDays,  // 휴일에 출근한 날
            int unconfirmedDays     // 출근했지만 퇴근하지 않은 날 (집계 미확정)
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WorkTime(
            int totalMin,           // 총 근로시간
            int regularMin,         // 소정
            int nightMin,           // 소정 중 야간
            int otExtendedMin,      // 초과 - 연장
            int otNightMin,         // 초과 - 야간
            int otHolidayMin        // 초과 - 휴일
    ) {
    }

    /** 명세서 총 근무일수 = 출근일 + 휴일 출근일 */
    public int workedDays() {
        return days.attendedDays() + days.holidayWorkedDays();
    }

    /** 야간 근로시간 전체 = 소정 중 야간 + 초과 야간 (명세서 ot_night_min, 야간수당 기준) */
    public int nightTotalMin() {
        return workTime.nightMin() + workTime.otNightMin();
    }

    /** 휴가 시간 (명세서 leave_min) */
    public int leaveMin() {
        return leave == null ? 0 : leave.leaveMin();
    }

    /** 무급휴가 시간 (무급휴가 차감 기준) */
    public int unpaidLeaveMin() {
        return leave == null ? 0 : leave.unpaidLeaveMin();
    }
}