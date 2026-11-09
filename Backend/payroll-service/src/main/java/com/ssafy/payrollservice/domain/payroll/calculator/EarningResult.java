package com.ssafy.payrollservice.domain.payroll.calculator;

/** 지급 항목 계산 결과 (단위: 원) */
public record EarningResult(
        int hourlyWage,         // 통상시급 (명세서 항목은 아님, 계산 근거)
        int basePay,            // 기본급
        int otExtendedPay,      // 연장근로수당
        int otNightPay,         // 야간근로수당
        int otHolidayPay,       // 휴일근로수당
        int annualLeavePay,     // 연차수당
        int bonus,              // 상여금
        int holidayBonus,       // 명절·휴가수당
        int unpaidLeaveDeduction // 무급휴가 차감 (0 이하, 명세서에 음수로 표시)
) {
    /** 지급합계 (무급휴가 차감 반영) */
    public int total() {
        return basePay + otExtendedPay + otNightPay + otHolidayPay
                + annualLeavePay + bonus + holidayBonus + unpaidLeaveDeduction;
    }
}