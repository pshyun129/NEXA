package com.ssafy.leaveservice.client.attendance;

import java.time.LocalDate;
import java.util.List;

public interface HolidayClient {
    HolidayResult findHolidays(LocalDate startDate, LocalDate endDate);

    enum HolidayType { LEGAL, COMPANY }
    record Holiday(LocalDate holidayDate, String name, HolidayType type) {}
    record HolidayResult(boolean verified, List<Holiday> holidays) {
        public static HolidayResult unavailable() {
            return new HolidayResult(false, List.of());
        }
    }
}
