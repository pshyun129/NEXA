package com.ssafy.payrollservice.domain.payroll.repository;

import com.ssafy.payrollservice.domain.payroll.entity.Payslip;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PayslipRepository extends JpaRepository<Payslip, Long> {

    boolean existsByEmpIdAndPayYearAndPayMonth(Long empId, Short payYear, Short payMonth);

    List<Payslip> findByEmpIdAndPayYearOrderByPayMonthDesc(Long empId, Short payYear);

    Optional<Payslip> findFirstByEmpIdOrderByPayYearDescPayMonthDesc(Long empId);

    // 기준 연월(yyyyMM)보다 앞선 명세서, 최근 월부터
    @Query("""
            select p from Payslip p
            where p.empId = :empId
              and (p.payYear * 100 + p.payMonth) < :yearMonth
            order by p.payYear desc, p.payMonth desc
            """)
    List<Payslip> findRecentBefore(@Param("empId") Long empId, @Param("yearMonth") int yearMonth, Pageable pageable);
}