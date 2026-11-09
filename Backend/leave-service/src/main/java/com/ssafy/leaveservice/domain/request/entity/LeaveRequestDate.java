package com.ssafy.leaveservice.domain.request.entity;

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
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "leave_request_date")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LeaveRequestDate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "request_date_id")
    private Long requestDateId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "leave_req_id", nullable = false)
    private LeaveRequest leaveRequest;

    @Column(name = "leave_date", nullable = false)
    private OffsetDateTime leaveDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "portion", nullable = false, length = 20)
    private LeaveRequestPortion portion;

    @Column(name = "start_at")
    private OffsetDateTime startAt;

    @Column(name = "end_at")
    private OffsetDateTime endAt;

    @Column(name = "scheduled_work_minutes")
    private Integer scheduledWorkMinutes;

    @Column(name = "requested_minutes", nullable = false)
    private int requestedMinutes;

    @Column(name = "deduct_minutes", nullable = false)
    private int deductMinutes;

    public static LeaveRequestDate requested(
            LeaveRequest request,
            OffsetDateTime leaveDate,
            LeaveRequestPortion portion,
            int requestedMinutes,
            int deductMinutes
    ) {
        LeaveRequestDate date = new LeaveRequestDate();
        date.leaveRequest = request;
        date.leaveDate = leaveDate;
        date.portion = portion;
        date.scheduledWorkMinutes = 480;
        date.requestedMinutes = requestedMinutes;
        date.deductMinutes = deductMinutes;
        return date;
    }
}
