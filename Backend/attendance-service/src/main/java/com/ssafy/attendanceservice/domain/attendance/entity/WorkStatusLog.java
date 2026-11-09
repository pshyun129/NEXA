package com.ssafy.attendanceservice.domain.attendance.entity;

import com.ssafy.attendanceservice.domain.attendance.entity.enums.WorkStatusSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "work_status_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkStatusLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;                     // 로그 ID

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "status_code", nullable = false)
    private WorkStatusCode workStatusCode;  // 상태 코드

    @Column(name = "emp_id", nullable = false)
    private Long empId;                     // 사원 ID

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;             // 근무일 (출근일 기준)

    @Column(name = "start_at", nullable = false)
    private OffsetDateTime startAt;         // 상태 시작 시각

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private WorkStatusSource source;        // 생성 주체

    @Column(name = "created_by", nullable = false)
    private Long createdBy;                 // 행을 만든 사람

    @Column(name = "reason")
    private String reason;                  // 보정, 결재 사유

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;              // 삭제 여부

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;       // 생성 날짜

    @Builder
    private WorkStatusLog(WorkStatusCode workStatusCode, Long empId, LocalDate workDate,
                          OffsetDateTime startAt, WorkStatusSource source, Long createdBy,
                          String reason) {
        this.workStatusCode = workStatusCode;
        this.empId = empId;
        this.workDate = workDate;
        this.startAt = startAt;
        this.source = source;
        this.createdBy = createdBy;
        this.reason = reason;
        this.isDeleted = false;
    }

    /** 상태 코드 변경 (퇴근 → 퇴근취소 등) */
    public void changeWorkStatusCode(WorkStatusCode workStatusCode) {
        this.workStatusCode = workStatusCode;
    }

    /** 삭제 처리 (실제 삭제하지 않고 이력으로 남김) */
    public void delete() {
        this.isDeleted = true;
    }
}