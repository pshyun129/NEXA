package com.ssafy.attendanceservice.domain.attendance.entity;

import com.ssafy.attendanceservice.domain.attendance.calculator.WorkTimeResult;
import com.ssafy.attendanceservice.global.util.DateTimeUtils;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

@Entity
@Table(name = "attendance_daily")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttendanceDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "att_id")
    private Long attId;                     // 집계 ID

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_group_id", nullable = false)
    private WorkGroup workGroup;            // 근무그룹

    @Column(name = "emp_id", nullable = false)
    private Long empId;                     // 사원 ID

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;             // 근무일 (출근일 기준)

    @Column(name = "clock_in")
    private OffsetDateTime clockIn;         // 출근 시각

    @Column(name = "clock_out")
    private OffsetDateTime clockOut;        // 퇴근 시각

    @Column(name = "total_min", nullable = false)
    private Integer totalMin;               // 총 근로시간(분)

    @Column(name = "regular_min", nullable = false)
    private Integer regularMin;             // 소정 근로시간(분)

    @Column(name = "break_min", nullable = false)
    private Integer breakMin;               // 휴게시간(분)

    @Column(name = "excluded_min", nullable = false)
    private Integer excludedMin;            // 근로 미포함 시간(분)

    @Column(name = "night_min", nullable = false)
    private Integer nightMin;               // 야간 근로(분)

    @Column(name = "ot_extended_min", nullable = false)
    private Integer otExtendedMin;          // 초과 - 연장(분)

    @Column(name = "ot_night_min", nullable = false)
    private Integer otNightMin;             // 초과 - 야간(분)

    @Column(name = "ot_holiday_min", nullable = false)
    private Integer otHolidayMin;           // 초과 - 휴일(분)

    @Column(name = "approved_ot_min", nullable = false)
    private Integer approvedOtMin;          // 결재 승인된 초과근로(분)

    @Column(name = "unapproved_ot_min", nullable = false)
    private Integer unapprovedOtMin;        // 미승인 초과근로(분)

    @Column(name = "is_confirmed", nullable = false)
    private Boolean isConfirmed;            // 퇴근 체크 확정 여부

    @Column(name = "is_holiday", nullable = false)
    private Boolean isHoliday;              // 휴일 여부

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;       // 마지막 재계산 시각

    /** 근무일 마감 시각 (출근한 날 다음날 이 시각) */
    public static final LocalTime WORK_DAY_CUTOFF = LocalTime.of(6, 0);

    @Builder
    private AttendanceDaily(WorkGroup workGroup, Long empId, LocalDate workDate,
                            OffsetDateTime clockIn, Boolean isHoliday) {
        this.workGroup = workGroup;
        this.empId = empId;
        this.workDate = workDate;
        this.clockIn = clockIn;
        this.isHoliday = isHoliday;
        this.totalMin = 0;
        this.regularMin = 0;
        this.breakMin = 0;
        this.excludedMin = 0;
        this.nightMin = 0;
        this.otExtendedMin = 0;
        this.otNightMin = 0;
        this.otHolidayMin = 0;
        this.approvedOtMin = 0;
        this.unapprovedOtMin = 0;
        this.isConfirmed = false;
    }

    /** 퇴근 처리 */
    public void clockOut(OffsetDateTime clockOut) {
        this.clockOut = clockOut;
        this.isConfirmed = true;
    }

    /** 퇴근 취소 (퇴근 시각 초기화) */
    public void cancelClockOut() {
        this.clockOut = null;
        this.isConfirmed = false;
    }

    /** 퇴근 여부 */
    public boolean isClockedOut() {
        return clockOut != null;
    }

    /**
     * 근무일 마감이 지났는가 (출근한 날 다음날 06:00).
     * 자정을 넘긴 야근은 마감 전까지 퇴근할 수 있고, 마감이 지나면 퇴근 누락으로 보고 퇴근·상태 변경을 받지 않는다
     */
    public boolean isPastWorkDayCutoff(OffsetDateTime now) {
        OffsetDateTime cutoff = workDate.plusDays(1).atTime(WORK_DAY_CUTOFF).atZone(DateTimeUtils.ZONE).toOffsetDateTime();
        return !now.isBefore(cutoff);
    }

    /** 퇴근 없이 근무일 마감이 지났는가 (퇴근 누락) */
    public boolean isUnclosedPastCutoff(OffsetDateTime now) {
        return !isClockedOut() && isPastWorkDayCutoff(now);
    }

    /** 근로시간 계산 결과 반영 */
    public void applyWorkTime(WorkTimeResult result) {
        this.totalMin = result.totalMin();
        this.regularMin = result.regularMin();
        this.breakMin = result.breakMin();
        this.excludedMin = result.excludedMin();
        this.nightMin = result.nightMin();
        this.otExtendedMin = result.otExtendedMin();
        this.otNightMin = result.otNightMin();
        this.otHolidayMin = result.otHolidayMin();
        this.approvedOtMin = result.approvedOtMin();
        this.unapprovedOtMin = result.unapprovedOtMin();
    }

    /** 로그 기준으로 출퇴근 시각 동기화 (관리자 수정, 결재 반영 후) */
    public void syncClock(OffsetDateTime clockIn, OffsetDateTime clockOut) {
        this.clockIn = clockIn;
        this.clockOut = clockOut;
        this.isConfirmed = clockOut != null;
    }

    /** 휴일 여부 변경 (휴일대체 승인 시 재판정) */
    public void changeHoliday(boolean isHoliday) {
        this.isHoliday = isHoliday;
    }
}