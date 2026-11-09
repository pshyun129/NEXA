package com.ssafy.leaveservice.domain.leavetype.repository;

import com.ssafy.leaveservice.domain.leavetype.entity.LeaveBalanceType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeaveBalanceTypeRepository extends JpaRepository<LeaveBalanceType, Long> {
}
