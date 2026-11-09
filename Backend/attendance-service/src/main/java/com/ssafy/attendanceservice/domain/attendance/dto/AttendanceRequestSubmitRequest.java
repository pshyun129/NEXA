package com.ssafy.attendanceservice.domain.attendance.dto;

import com.ssafy.attendanceservice.domain.attendance.entity.enums.AttendanceRequestType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * 근태 신청서 상신 요청. 신청 내용은 미리보기와 같고, 사유가 추가된다.
 * 결재선은 받지 않는다. 결재 서비스가 위임전결 규정과 조직도로 만든다.
 */
public record AttendanceRequestSubmitRequest(
        @NotNull AttendanceRequestType reqType,                 // 신청 유형
        @NotNull LocalDate targetDate,                          // 대상일
        LocalDate substDate,                                    // 대체 휴일 (HOLIDAY_SUBST)
        @NotBlank @Size(max = 500) String reason,               // 신청 사유
        List<AttendanceRequestPreviewRequest.Item> items        // 신청 상세 (TIME_FIX, OVERTIME)
) {

    /** 미리보기와 같은 검증을 하기 위한 변환 */
    public AttendanceRequestPreviewRequest toPreviewRequest() {
        return new AttendanceRequestPreviewRequest(reqType, targetDate, substDate, items);
    }

    /** items가 없으면 빈 목록 */
    public List<AttendanceRequestPreviewRequest.Item> itemsOrEmpty() {
        return items == null ? List.of() : items;
    }
}
