package com.ssafy.payrollservice.domain.payroll.repository;

import com.ssafy.payrollservice.domain.payroll.entity.PayGrade;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayGradeRepository extends JpaRepository<PayGrade, Long> {
}