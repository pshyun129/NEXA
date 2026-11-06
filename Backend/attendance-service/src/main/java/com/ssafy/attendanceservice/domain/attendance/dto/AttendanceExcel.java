package com.ssafy.attendanceservice.domain.attendance.dto;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** 근태 통계 엑셀 파일 */
public record AttendanceExcel(
        String fileName,        // 다운로드 파일명 (attendance-20261001-20261031.xlsx)
        byte[] content          // xlsx 내용
) {
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    public static AttendanceExcel of(LocalDate from, LocalDate to, byte[] content) {
        return new AttendanceExcel(
                "attendance-" + from.format(BASIC_DATE) + "-" + to.format(BASIC_DATE) + ".xlsx", content);
    }
}
