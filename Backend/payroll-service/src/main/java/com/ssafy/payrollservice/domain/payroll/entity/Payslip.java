package com.ssafy.payrollservice.domain.payroll.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "payslip")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payslip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payslip_id")
    private Long payslipId;             // 명세서 ID

    @Column(name = "emp_id", nullable = false)
    private Long empId;                 // 사원 ID

    @Column(name = "pay_year", nullable = false)
    private Short payYear;              // 급여 연도

    @Column(name = "pay_month")
    private Short payMonth;             // 급여 월

    @Column(name = "pay_date")
    private LocalDate payDate;          // 지급일

    // 생성 시점 사원 정보 스냅샷
    @Column(name = "emp_name", length = 50, nullable = false)
    private String empName;             // 사원명

    @Column(name = "dept_name", length = 50, nullable = false)
    private String deptName;            // 부서명

    @Column(name = "position_name", length = 50)
    private String positionName;        // 직급명 (직위 폐지로 직급을 표시, 이관 명세서는 원본 직위명)

    @Column(name = "hire_date", nullable = false)
    private LocalDate hireDate;         // 입사일

    // 근태 집계 스냅샷
    @Column(name = "work_days", nullable = false)
    private Short workDays;             // 총 근무일수

    @Column(name = "total_min", nullable = false)
    private Integer totalMin;           // 총 근로시간(분)

    @Column(name = "regular_min", nullable = false)
    private Integer regularMin;         // 기본 근로시간(분)

    @Column(name = "ot_extended_min", nullable = false)
    private Integer otExtendedMin;      // 연장 근로시간(분)

    @Column(name = "ot_night_min", nullable = false)
    private Integer otNightMin;         // 야간 근로시간(분)

    @Column(name = "ot_holiday_min", nullable = false)
    private Integer otHolidayMin;       // 휴일 근로시간(분)

    @Column(name = "leave_min", nullable = false)
    private Integer leaveMin;           // 휴가시간(분)

    // 금액
    @Column(name = "total_pay")
    private Integer totalPay;           // 지급합계

    @Column(name = "total_deduction")
    private Integer totalDeduction;     // 공제합계

    @Column(name = "net_pay")
    private Integer netPay;             // 실지급액

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;   // 생성일시

    @Builder
    private Payslip(Long empId, Short payYear, Short payMonth, LocalDate payDate,
                    String empName, String deptName, String positionName, LocalDate hireDate,
                    Short workDays, Integer totalMin, Integer regularMin, Integer otExtendedMin,
                    Integer otNightMin, Integer otHolidayMin, Integer leaveMin,
                    Integer totalPay, Integer totalDeduction, Integer netPay) {
        this.empId = empId;
        this.payYear = payYear;
        this.payMonth = payMonth;
        this.payDate = payDate;
        this.empName = empName;
        this.deptName = deptName;
        this.positionName = positionName;
        this.hireDate = hireDate;
        this.workDays = workDays;
        this.totalMin = totalMin;
        this.regularMin = regularMin;
        this.otExtendedMin = otExtendedMin;
        this.otNightMin = otNightMin;
        this.otHolidayMin = otHolidayMin;
        this.leaveMin = leaveMin;
        this.totalPay = totalPay;
        this.totalDeduction = totalDeduction;
        this.netPay = netPay;
    }
}