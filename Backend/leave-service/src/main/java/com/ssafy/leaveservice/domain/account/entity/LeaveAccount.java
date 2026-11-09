package com.ssafy.leaveservice.domain.account.entity;

import com.ssafy.leaveservice.domain.leavetype.entity.LeaveBalanceType;
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
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "leave_account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LeaveAccount {

    public static LeaveAccount open(Long employeeId, LeaveBalanceType balanceType) {
        LeaveAccount account = new LeaveAccount();
        account.employeeId = employeeId;
        account.balanceType = balanceType;
        return account;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "emp_id", nullable = false)
    private Long employeeId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "balance_type_id", nullable = false)
    private LeaveBalanceType balanceType;
}
