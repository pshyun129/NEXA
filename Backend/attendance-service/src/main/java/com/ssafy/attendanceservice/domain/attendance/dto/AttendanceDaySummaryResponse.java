package com.ssafy.attendanceservice.domain.attendance.dto;

import com.ssafy.attendanceservice.domain.attendance.calculator.TimeRange;
import com.ssafy.attendanceservice.domain.attendance.entity.AttendanceDaily;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AnomalyType;
import com.ssafy.attendanceservice.global.util.DateTimeUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 주간/월간 조회의 하루 요약 */
public record AttendanceDaySummaryResponse(
        LocalDate workDate,             // 근무일
        Boolean isHoliday,              // 휴일 여부
        OffsetDateTime clockIn,         // 출근 시각
        OffsetDateTime clockOut,        // 퇴근 시각
        WorkTimeResponse workTime,      // 근로시간 상세
        List<AnomalyType> anomalies,    // 근태이상
        List<LeaveResponse> leaves      // 휴가 (오전·오후 반차를 따로 쓰면 두 건)
) {
    public static AttendanceDaySummaryResponse of(AttendanceDaily daily, List<AnomalyType> anomalies,
                                                  List<LeaveResponse> leaves, List<TimeRange> unapprovedOtRanges) {
        return new AttendanceDaySummaryResponse(
                daily.getWorkDate(),
                daily.getIsHoliday(),
                DateTimeUtils.toSeoul(daily.getClockIn()),
                DateTimeUtils.toSeoul(daily.getClockOut()),
                WorkTimeResponse.from(daily, unapprovedOtRanges),
                anomalies,
                leaves
        );
    }

    /** 근태 기록이 없는 날 */
    public static AttendanceDaySummaryResponse empty(LocalDate workDate, boolean isHoliday, List<LeaveResponse> leaves) {
        return new AttendanceDaySummaryResponse(
                workDate, isHoliday, null, null, WorkTimeResponse.empty(), List.of(), leaves);
    }

    /** 그날 휴가 시간(분). 종일 480, 반차 240 */
    public int leaveMin() {
        return leaves.stream().mapToInt(leave -> leave.portion().minutes()).sum();
    }

    /** 그날 무급휴가 시간(분) */
    public int unpaidLeaveMin() {
        return leaves.stream().filter(leave -> !leave.isPaid()).mapToInt(leave -> leave.portion().minutes()).sum();
    }
}
