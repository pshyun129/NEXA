package com.ssafy.leaveservice.domain.leavetype.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "leave_balance_type")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LeaveBalanceType {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "balance_type_id")
    private Long balanceTypeId;

    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "is_active", nullable = false)
    private boolean active;
}
