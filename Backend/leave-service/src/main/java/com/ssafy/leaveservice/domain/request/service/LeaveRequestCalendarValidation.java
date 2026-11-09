package com.ssafy.leaveservice.domain.request.service;

import com.ssafy.leaveservice.client.attendance.HolidayClient.Holiday;
import com.ssafy.leaveservice.client.attendance.HolidayClient.HolidayResult;
import com.ssafy.leaveservice.domain.request.dto.LeaveRequestPreviewResponse.Reason;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class LeaveRequestCalendarValidation {
    private LeaveRequestCalendarValidation() {
    }

    public static List<Reason> validateDate(LocalDate date, HolidayResult holidays) {
        List<Reason> reasons = new ArrayList<>();
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            reasons.add(new Reason("WEEKEND", date, "주말에는 휴가를 신청할 수 없습니다."));
        }
        if (holidays.verified()) {
            for (Holiday holiday : holidays.holidays()) {
                if (holiday.holidayDate().equals(date)) {
                    reasons.add(new Reason("HOLIDAY", date, holiday.name() + "에는 휴가를 신청할 수 없습니다."));
                }
            }
        }
        return reasons;
    }
}
