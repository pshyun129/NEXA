package com.ssafy.attendanceservice.domain.attendance.entity;

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
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "employee_work_group")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmployeeWorkGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;                        // 배정 ID

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_group_id", nullable = false)
    private WorkGroup workGroup;            // 근무그룹

    @Column(name = "emp_id", nullable = false)
    private Long empId;                     // 사원 ID

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;            // 근무그룹 적용 시작 일자

    @Column(name = "end_date")
    private LocalDate endDate;              // 근무그룹 적용 종료 일자

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;       // 생성 날짜

    @Builder
    private EmployeeWorkGroup(WorkGroup workGroup, Long empId, LocalDate startDate, LocalDate endDate) {
        this.workGroup = workGroup;
        this.empId = empId;
        this.startDate = startDate;
        this.endDate = endDate;
    }
}