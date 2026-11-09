package com.ssafy.attendanceservice.domain.attendance.calculator;

/** 근로시간 계산 결과 (단위: 분). attendance_daily 집계 컬럼과 1:1 */
public record WorkTimeResult(
        int totalMin,           // 총 근로시간
        int regularMin,         // 소정
        int breakMin,           // 휴게 (추가 차감분)
        int excludedMin,        // 근로 미포함 (업무종료, 휴가 등)
        int nightMin,           // 소정 중 야간
        int otExtendedMin,      // 초과 - 연장
        int otNightMin,         // 초과 - 야간
        int otHolidayMin,       // 초과 - 휴일
        int approvedOtMin,      // 승인 초과근로
        int unapprovedOtMin     // 미승인 초과근로
) {
    /** 퇴근 전 또는 퇴근취소 시 */
    public static WorkTimeResult empty() {
        return new WorkTimeResult(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}