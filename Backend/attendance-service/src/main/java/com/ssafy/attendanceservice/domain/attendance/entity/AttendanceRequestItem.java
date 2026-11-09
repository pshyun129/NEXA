package com.ssafy.attendanceservice.domain.attendance.entity;

import com.ssafy.attendanceservice.domain.attendance.entity.enums.RequestItemAction;
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

import java.time.OffsetDateTime;

@Entity
@Table(name = "attendance_request_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttendanceRequestItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "item_id")
    private Long itemId;                            // 상세 ID

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "att_req_id", nullable = false)
    private AttendanceRequest attendanceRequest;    // 신청서

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "status_code", nullable = false)
    private WorkStatusCode workStatusCode;          // 근무 상태

    @Column(name = "target_log_id")
    private Long targetLogId;                       // 대상 로그 ID (수정/삭제 시)

    @Column(name = "start_at", nullable = false)
    private OffsetDateTime startAt;                 // 시작 시각

    @Column(name = "end_at")
    private OffsetDateTime endAt;                   // 종료 시각

    @Enumerated(EnumType.STRING)
    @Column(name = "action")
    private RequestItemAction action;               // 처리 구분

    @Builder
    private AttendanceRequestItem(WorkStatusCode workStatusCode, Long targetLogId,
                                  OffsetDateTime startAt, OffsetDateTime endAt, RequestItemAction action) {
        this.workStatusCode = workStatusCode;
        this.targetLogId = targetLogId;
        this.startAt = startAt;
        this.endAt = endAt;
        this.action = action;
    }

    /** 신청서 연결 (AttendanceRequest.addItem에서만 호출) */
    void assignRequest(AttendanceRequest attendanceRequest) {
        this.attendanceRequest = attendanceRequest;
    }
}
