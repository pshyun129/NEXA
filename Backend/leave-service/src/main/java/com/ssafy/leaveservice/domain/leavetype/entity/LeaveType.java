package com.ssafy.leaveservice.domain.leavetype.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "leave_type")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LeaveType {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "leave_type_id")
    private Long leaveTypeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "balance_type_id")
    private LeaveBalanceType balanceType;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "is_paid", nullable = false)
    private boolean paid;

    @Enumerated(EnumType.STRING)
    @Column(name = "balance_mode", nullable = false, length = 20)
    private BalanceMode balanceMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_unit", nullable = false, length = 20)
    private RequestUnit requestUnit;

    @Column(name = "max_minutes")
    private Integer maxMinutes;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    // TODO: 향후 신규 휴가 신청 API는 이 활성 상태를 검사해야 한다. 기존 신청·원장 조회는 유지한다.
    public void updateDetails(
            String name,
            boolean paid,
            BalanceMode balanceMode,
            LeaveBalanceType balanceType,
            RequestUnit requestUnit,
            Integer maxMinutes,
            boolean active
    ) {
        this.name = name;
        this.paid = paid;
        this.balanceMode = balanceMode;
        this.balanceType = balanceType;
        this.requestUnit = requestUnit;
        this.maxMinutes = maxMinutes;
        this.active = active;
    }

    @Builder
    private LeaveType(
            LeaveBalanceType balanceType,
            String name,
            boolean paid,
            BalanceMode balanceMode,
            RequestUnit requestUnit,
            Integer maxMinutes,
            boolean active
    ) {
        this.balanceType = balanceType;
        this.name = name;
        this.paid = paid;
        this.balanceMode = balanceMode;
        this.requestUnit = requestUnit;
        this.maxMinutes = maxMinutes;
        this.active = active;
    }
}
