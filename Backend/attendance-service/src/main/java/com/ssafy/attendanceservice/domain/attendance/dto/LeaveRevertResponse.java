package com.ssafy.attendanceservice.domain.attendance.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 휴가 원복 결과. reverted가 false면 이미 원복했거나 근태에 저장된 적이 없는 휴가 (이 경우도 성공).
 * 원복한 날 중 근태 기록이 있는 날은 정상 근무 기준으로 다시 판정했다
 */
public record LeaveRevertResponse(
        Long leaveRequestId,            // 휴가 신청 ID
        UUID cancelRequestId,           // 받은 취소 요청 ID
        boolean reverted,               // 이번 요청으로 지운 휴가가 있는가
        List<LocalDate> revertedDates   // 지운 휴가 날짜 (날짜순)
) {
    public static LeaveRevertResponse of(Long leaveRequestId, UUID cancelRequestId, List<LocalDate> revertedDates) {
        return new LeaveRevertResponse(leaveRequestId, cancelRequestId, !revertedDates.isEmpty(), revertedDates);
    }
}
