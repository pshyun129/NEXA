package com.ssafy.attendanceservice.domain.attendance.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** 승인된 휴가 원복 요청 (휴가 서비스의 관리자 취소). cancelRequestId는 취소 작업 ID로, 로그와 응답에만 남긴다 */
public record LeaveRevertRequest(
        @NotNull UUID cancelRequestId   // 휴가 서비스의 취소 요청 ID
) {
}
