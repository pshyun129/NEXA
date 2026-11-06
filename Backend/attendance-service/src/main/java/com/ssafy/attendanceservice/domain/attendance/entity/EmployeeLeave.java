package com.ssafy.attendanceservice.domain.attendance.entity;

import com.ssafy.attendanceservice.domain.attendance.entity.enums.LeavePortion;
import com.ssafy.attendanceservice.global.util.DateTimeUtils;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/** 승인된 휴가 (휴가 서비스 신청의 날짜별 한 줄) */
@Entity
@Table(name = "employee_leave")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmployeeLeave {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;                        // 휴가 ID

    @Column(name = "leave_req_id", nullable = false)
    private Long leaveReqId;                // 휴가 서비스 신청 ID

    @Column(name = "emp_id", nullable = false)
    private Long empId;                     // 사원 ID

    @Column(name = "leave_date", nullable = false)
    private LocalDate leaveDate;            // 휴가 일자

    @Enumerated(EnumType.STRING)
    @Column(name = "portion", nullable = false, length = 20)
    private LeavePortion portion;           // 휴가 구분

    @Column(name = "start_at")
    private OffsetDateTime startAt;         // 반차 시작 (종일이면 null)

    @Column(name = "end_at")
    private OffsetDateTime endAt;           // 반차 종료 (종일이면 null)

    @Column(name = "leave_type_name", nullable = false, length = 100)
    private String leaveTypeName;           // 휴가 유형명 (연차, 병가 등)

    @Column(name = "is_paid", nullable = false)
    private Boolean isPaid;                 // 유급 여부

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;       // 생성 날짜

    /** 반차 시각은 구분별 고정값으로 채운다 */
    @Builder
    private EmployeeLeave(Long leaveReqId, Long empId, LocalDate leaveDate, LeavePortion portion,
                          String leaveTypeName, Boolean isPaid) {
        this.leaveReqId = leaveReqId;
        this.empId = empId;
        this.leaveDate = leaveDate;
        this.portion = portion;
        this.startAt = atSeoul(leaveDate, portion.startTime());
        this.endAt = atSeoul(leaveDate, portion.endTime());
        this.leaveTypeName = leaveTypeName;
        this.isPaid = isPaid;
    }

    private static OffsetDateTime atSeoul(LocalDate date, LocalTime time) {
        return time == null ? null : date.atTime(time).atZone(DateTimeUtils.ZONE).toOffsetDateTime();
    }
}
