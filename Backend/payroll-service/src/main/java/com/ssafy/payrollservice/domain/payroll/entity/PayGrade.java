package com.ssafy.payrollservice.domain.payroll.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "pay_grade")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PayGrade {

    @Id
    @Column(name = "grade_id")
    private Long gradeId;           // 직급 ID (user-service grade.grade_id)

    @Column(name = "base_amount", nullable = false)
    private Integer baseAmount;     // 월 기본급

    @Builder
    private PayGrade(Long gradeId, Integer baseAmount) {
        this.gradeId = gradeId;
        this.baseAmount = baseAmount;
    }
}