package com.ssafy.payrollservice.domain.payroll.entity;

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

@Entity
@Table(name = "payslip_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PayslipItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;                // ID

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payslip_id", nullable = false)
    private Payslip payslip;        // 명세서

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private PayItem payItem;        // 급여 항목

    @Column(name = "amount", nullable = false)
    private Integer amount;         // 금액

    @Builder
    private PayslipItem(Payslip payslip, PayItem payItem, Integer amount) {
        this.payslip = payslip;
        this.payItem = payItem;
        this.amount = amount;
    }
}