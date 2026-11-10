package com.ssafy.attendanceservice.domain.attendance.dto;

import com.ssafy.attendanceservice.domain.attendance.calculator.TimeRange;
import com.ssafy.attendanceservice.domain.attendance.entity.AttendanceDaily;
import com.ssafy.attendanceservice.domain.attendance.entity.WorkStatusLog;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AnomalyType;
import com.ssafy.attendanceservice.global.util.DateTimeUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 일별 근태 타임라인 */
public record DailyAttendanceResponse(
        LocalDate workDate,                         // 근무일
        String workGroupName,                       // 적용 근무그룹명
        Boolean isHoliday,                          // 휴일 여부
        OffsetDateTime clockIn,                     // 출근 시각
        OffsetDateTime clockOut,                    // 퇴근 시각
        WorkTimeResponse workTime,                  // 근로시간 상세
        List<WorkStatusRecordResponse> records,     // 상태 기록 (시간순)
        List<AnomalyType> anomalies                 // 근태이상
) {
    public static DailyAttendanceResponse of(AttendanceDaily daily,
                                             List<WorkStatusLog> logs,
                                             List<AnomalyType> anomalies,
                                             List<TimeRange> unapprovedOtRanges) {
        return new DailyAttendanceResponse(
                daily.getWorkDate(),
                daily.getWorkGroup().getName(),
                daily.getIsHoliday(),
                DateTimeUtils.toSeoul(daily.getClockIn()),
                DateTimeUtils.toSeoul(daily.getClockOut()),
                WorkTimeResponse.from(daily, unapprovedOtRanges),
                logs.stream().map(WorkStatusRecordResponse::from).toList(),
                anomalies
        );
    }

    /** 근태 기록이 없는 날 (출근 안 한 날, 미래 날짜) */
    public static DailyAttendanceResponse empty(LocalDate workDate, String workGroupName, boolean isHoliday) {
        return new DailyAttendanceResponse(
                workDate,
                workGroupName,
                isHoliday,
                null,
                null,
                WorkTimeResponse.empty(),
                List.of(),
                List.of()
        );
    }
}