package com.ssafy.payrollservice.domain.payroll.repository;

import com.ssafy.payrollservice.domain.payroll.entity.PayItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayItemRepository extends JpaRepository<PayItem, Long> {
}