package com.ssafy.attendanceservice.domain.attendance.dto;

import com.ssafy.attendanceservice.domain.attendance.entity.WorkStatusCode;

/** 근무 상태 코드 */
public record WorkStatusCodeResponse(
        String statusCode,      // 상태 코드
        String statusName,      // 상태명
        Boolean countAsWork     // 근로시간 포함 여부
) {
    public static WorkStatusCodeResponse from(WorkStatusCode code) {
        return new WorkStatusCodeResponse(code.getStatusCode(), code.getName(), code.getCountAsWork());
    }
}