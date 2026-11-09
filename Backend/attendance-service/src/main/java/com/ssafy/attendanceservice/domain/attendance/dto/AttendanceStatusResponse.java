package com.ssafy.attendanceservice.domain.attendance.dto;

import com.ssafy.attendanceservice.domain.attendance.entity.AttendanceDaily;
import com.ssafy.attendanceservice.domain.attendance.entity.WorkStatusCode;
import com.ssafy.attendanceservice.global.util.DateTimeUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/** 근태 위젯 상태 응답. 출근/퇴근/상태변경/today API가 공통으로 사용한다. */
public record AttendanceStatusResponse(
        LocalDate workDate,                             // 근무일
        String statusCode,                              // 현재 상태 코드
        String statusName,                              // 현재 상태명
        Boolean isWorking,                              // 현재 상태가 근로시간에 포함되는지
        OffsetDateTime clockIn,                         // 출근 시각
        OffsetDateTime clockOut,                        // 퇴근 시각
        List<WorkStatusCodeResponse> ableStatusList     // 지금 바꿀 수 있는 상태 목록
) {

    public static AttendanceStatusResponse of(AttendanceDaily daily,
                                              WorkStatusCode current,
                                              List<WorkStatusCode> ableStatuses) {
        return new AttendanceStatusResponse(
                daily.getWorkDate(),
                current.getStatusCode(),
                current.getName(),
                current.getCountAsWork(),
                DateTimeUtils.toSeoul(daily.getClockIn()),
                DateTimeUtils.toSeoul(daily.getClockOut()),
                ableStatuses.stream().map(WorkStatusCodeResponse::from).toList()
        );
    }

    /** 출근 전 (오늘 근태 기록 없음) */
    public static AttendanceStatusResponse beforeClockIn(LocalDate today) {
        return new AttendanceStatusResponse(
                today,
                null,
                null,
                false,
                null,
                null,
                List.of()
        );
    }
}