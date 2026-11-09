package com.ssafy.attendanceservice.domain.attendance.dto;

import com.ssafy.attendanceservice.domain.attendance.calculator.WorkTimeResult;
import com.ssafy.attendanceservice.domain.attendance.entity.AttendanceDaily;

/** 근로시간 상세 (단위: 분) */
public record WorkTimeResponse(
        int totalMin,           // 총 근로시간
        int regularMin,         // 소정
        int nightMin,           // 소정 중 야간
        int otExtendedMin,      // 초과 - 연장
        int otNightMin,         // 초과 - 야간
        int otHolidayMin,       // 초과 - 휴일
        int breakMin,           // 휴게 (추가 차감분)
        int excludedMin,        // 근로 미포함
        int approvedOtMin,      // 승인 초과근로
        int unapprovedOtMin     // 미승인 초과근로
) {
    public static WorkTimeResponse from(AttendanceDaily daily) {
        return new WorkTimeResponse(
                daily.getTotalMin(),
                daily.getRegularMin(),
                daily.getNightMin(),
                daily.getOtExtendedMin(),
                daily.getOtNightMin(),
                daily.getOtHolidayMin(),
                daily.getBreakMin(),
                daily.getExcludedMin(),
                daily.getApprovedOtMin(),
                daily.getUnapprovedOtMin()
        );
    }

    /** 계산 결과로 생성 (저장하지 않은 시뮬레이션 결과용) */
    public static WorkTimeResponse from(WorkTimeResult result) {
        return new WorkTimeResponse(
                result.totalMin(),
                result.regularMin(),
                result.nightMin(),
                result.otExtendedMin(),
                result.otNightMin(),
                result.otHolidayMin(),
                result.breakMin(),
                result.excludedMin(),
                result.approvedOtMin(),
                result.unapprovedOtMin()
        );
    }

    public static WorkTimeResponse empty() {
        return new WorkTimeResponse(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}