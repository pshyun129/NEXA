package com.ssafy.attendanceservice.domain.attendance.dto;

import com.ssafy.attendanceservice.domain.attendance.entity.WorkGroup;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AnomalyType;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.LeavePortion;

import java.time.YearMonth;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 급여용 월별 근태 집계 */
public record AttendanceSummaryResponse(
        Long empId,                         // 사원 ID
        YearMonth month,                    // 집계 월
        String workGroupName,               // 근무그룹명
        Days days,                          // 일수
        WorkTime workTime,                  // 근로시간 합계 (분)
        Leave leave,                        // 휴가 합계
        Map<AnomalyType, Integer> anomalies // 근태이상 유형별 횟수
) {

    public record Leave(
            int leaveDays,          // 근무일 중 종일 휴가일 (근무일 - 출근일 - 종일 휴가일 = 결근일)
            int leaveMin,           // 휴가 시간 (종일 480, 반차 240)
            int unpaidLeaveMin      // 그중 무급휴가 시간 (급여 공제)
    ) {
        public static Leave of(List<AttendanceDaySummaryResponse> days) {
            return new Leave(
                    (int) days.stream()
                            .filter(d -> !d.isHoliday() && d.leaveMin() >= LeavePortion.FULL.minutes())
                            .count(),
                    days.stream().mapToInt(AttendanceDaySummaryResponse::leaveMin).sum(),
                    days.stream().mapToInt(AttendanceDaySummaryResponse::unpaidLeaveMin).sum());
        }
    }

    public record Days(
            int workDays,           // 근무일 수 (휴일 제외)
            int attendedDays,       // 근무일 중 출근한 날
            int holidayWorkedDays,  // 휴일에 출근한 날
            int unconfirmedDays     // 출근했지만 퇴근하지 않은 날 (집계 미확정)
    ) {
        public static Days of(List<AttendanceDaySummaryResponse> days) {
            List<AttendanceDaySummaryResponse> attended = days.stream().filter(d -> d.clockIn() != null).toList();
            return new Days(
                    (int) days.stream().filter(d -> !d.isHoliday()).count(),
                    (int) attended.stream().filter(d -> !d.isHoliday()).count(),
                    (int) attended.stream().filter(AttendanceDaySummaryResponse::isHoliday).count(),
                    (int) attended.stream().filter(d -> d.clockOut() == null).count());
        }
    }

    public record WorkTime(
            int totalMin,           // 총 근로시간
            int regularMin,         // 소정
            int nightMin,           // 소정 중 야간
            int otExtendedMin,      // 초과 - 연장
            int otNightMin,         // 초과 - 야간
            int otHolidayMin,       // 초과 - 휴일
            int approvedOtMin,      // 승인 초과근로
            int unapprovedOtMin     // 미승인 초과근로
    ) {
        public static WorkTime of(List<AttendanceDaySummaryResponse> days) {
            return new WorkTime(
                    days.stream().mapToInt(d -> d.workTime().totalMin()).sum(),
                    days.stream().mapToInt(d -> d.workTime().regularMin()).sum(),
                    days.stream().mapToInt(d -> d.workTime().nightMin()).sum(),
                    days.stream().mapToInt(d -> d.workTime().otExtendedMin()).sum(),
                    days.stream().mapToInt(d -> d.workTime().otNightMin()).sum(),
                    days.stream().mapToInt(d -> d.workTime().otHolidayMin()).sum(),
                    days.stream().mapToInt(d -> d.workTime().approvedOtMin()).sum(),
                    days.stream().mapToInt(d -> d.workTime().unapprovedOtMin()).sum());
        }
    }

    public static AttendanceSummaryResponse of(Long empId, YearMonth month, WorkGroup workGroup,
                                               List<AttendanceDaySummaryResponse> days) {
        return new AttendanceSummaryResponse(empId, month, workGroup.getName(),
                Days.of(days), WorkTime.of(days), Leave.of(days), anomaliesOf(days));
    }

    /** 근태이상 유형별 횟수 (관리자 기간별 통계도 같이 쓴다). 모든 유형을 0으로 채워서 응답 형태를 고정 */
    public static Map<AnomalyType, Integer> anomaliesOf(List<AttendanceDaySummaryResponse> days) {
        Map<AnomalyType, Integer> anomalies = new EnumMap<>(AnomalyType.class);
        for (AnomalyType type : AnomalyType.values()) {
            anomalies.put(type, 0);
        }
        days.forEach(d -> d.anomalies().forEach(type -> anomalies.merge(type, 1, Integer::sum)));
        return anomalies;
    }
}
