package com.ssafy.attendanceservice.domain.attendance.entity;

import com.ssafy.attendanceservice.domain.attendance.entity.enums.AnomalyType;
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

import java.time.OffsetDateTime;

@Entity
@Table(name = "attendance_anomaly")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttendanceAnomaly {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "anomaly_id")
    private Long anomalyId;                     // 이상 ID

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "att_id", nullable = false)
    private AttendanceDaily attendanceDaily;    // 일일 근태

    @Enumerated(EnumType.STRING)
    @Column(name = "anomaly_type", nullable = false)
    private AnomalyType anomalyType;            // 이상 유형

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;           // 판정 시각

    @Builder
    private AttendanceAnomaly(AttendanceDaily attendanceDaily, AnomalyType anomalyType) {
        this.attendanceDaily = attendanceDaily;
        this.anomalyType = anomalyType;
    }
}