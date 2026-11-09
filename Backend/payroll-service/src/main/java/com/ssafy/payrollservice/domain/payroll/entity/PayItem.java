package com.ssafy.payrollservice.domain.payroll.entity;

import com.ssafy.payrollservice.domain.payroll.entity.enums.PayItemKind;
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

@Entity
@Table(name = "pay_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PayItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "item_id")
    private Long itemId;            // 항목 ID

    @Column(name = "item_code", length = 30, nullable = false)
    private String itemCode;        // 급여항목 코드

    @Column(name = "name", length = 50, nullable = false)
    private String name;            // 급여항목명

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private PayItemKind kind;       // 종류 (지급/공제)

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;      // 정렬 순서

    @Builder
    private PayItem(String itemCode, String name, PayItemKind kind, Integer sortOrder) {
        this.itemCode = itemCode;
        this.name = name;
        this.kind = kind;
        this.sortOrder = sortOrder;
    }
}