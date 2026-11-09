package com.ssafy.attendanceservice.domain.attendance.entity;

import com.ssafy.attendanceservice.domain.attendance.entity.enums.WorkType;
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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;

@Entity
@Table(name = "work_group")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "work_group_id")
    private Long workGroupId;               // 근무그룹 ID

    @Column(name = "name", length = 50, nullable = false)
    private String name;                    // 그룹명

    @Enumerated(EnumType.STRING)
    @Column(name = "work_type", nullable = false)
    private WorkType workType;              // 근무 유형

    @Column(name = "start_time")
    private LocalTime startTime;            // 소정 출근 시각

    @Column(name = "end_time")
    private LocalTime endTime;              // 소정 퇴근 시각

    @Column(name = "break_minutes", nullable = false)
    private Integer breakMinutes;           // 휴게시간(분)

    @Column(name = "work_days", nullable = false)
    private Integer workDays;               // 근무 요일 (비트마스크: 월=1 ... 일=64)

    @Column(name = "daily_std_minutes", nullable = false)
    private Integer dailyStdMinutes;        // 일일 소정 근로시간(분)

    @Column(name = "weekly_std_hours", nullable = false)
    private Integer weeklyStdHours;         // 주 소정 근로시간

    @Column(name = "weekly_max_hours", nullable = false)
    private Integer weeklyMaxHours;         // 주 최대 근로시간

    @Column(name = "is_default", nullable = false)
    private Boolean isDefault;              // 기본 그룹 여부

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;       // 생성 날짜

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;       // 수정 날짜

    @Builder
    private WorkGroup(String name, WorkType workType, LocalTime startTime, LocalTime endTime,
                      Integer breakMinutes, Integer workDays, Integer dailyStdMinutes,
                      Integer weeklyStdHours, Integer weeklyMaxHours, Boolean isDefault) {
        this.name = name;
        this.workType = workType;
        this.startTime = startTime;
        this.endTime = endTime;
        this.breakMinutes = breakMinutes;
        this.workDays = workDays;
        this.dailyStdMinutes = dailyStdMinutes;
        this.weeklyStdHours = weeklyStdHours;
        this.weeklyMaxHours = weeklyMaxHours;
        this.isDefault = isDefault;
    }

    /** 해당 요일이 근무 요일인지 (비트마스크: 월=1, 화=2, 수=4, 목=8, 금=16, 토=32, 일=64) */
    public boolean isWorkDay(DayOfWeek dayOfWeek) {
        int bit = 1 << (dayOfWeek.getValue() - 1);
        return (workDays & bit) != 0;
    }
}