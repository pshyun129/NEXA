package com.ssafy.payrollservice.domain.payroll.repository;

import com.ssafy.payrollservice.domain.payroll.entity.PayslipItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PayslipItemRepository extends JpaRepository<PayslipItem, Long> {

    // 명세서 항목 (급여 항목 함께 조회, 정렬 순서대로)
    @Query("""
            select psi from PayslipItem psi
            join fetch psi.payItem pi
            where psi.payslip.payslipId = :payslipId
            order by pi.sortOrder
            """)
    List<PayslipItem> findWithPayItemByPayslipId(@Param("payslipId") Long payslipId);

    // 명세서의 특정 항목 금액
    @Query("""
            select psi.amount from PayslipItem psi
            where psi.payslip.payslipId = :payslipId
              and psi.payItem.itemCode = :itemCode
            """)
    Optional<Integer> findAmount(@Param("payslipId") Long payslipId, @Param("itemCode") String itemCode);

    // 기간(yyyyMM, 양 끝 포함) 내 명세서의 특정 항목 합계
    @Query("""
            select coalesce(sum(psi.amount), 0) from PayslipItem psi
            where psi.payslip.empId = :empId
              and psi.payItem.itemCode = :itemCode
              and (psi.payslip.payYear * 100 + psi.payslip.payMonth) between :fromYearMonth and :toYearMonth
            """)
    long sumAmount(@Param("empId") Long empId, @Param("itemCode") String itemCode,
                   @Param("fromYearMonth") int fromYearMonth, @Param("toYearMonth") int toYearMonth);
}