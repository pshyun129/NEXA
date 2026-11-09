package com.ssafy.leaveservice.domain.request.repository;

import com.ssafy.leaveservice.domain.request.entity.LeaveRequestDate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeaveRequestDateRepository extends JpaRepository<LeaveRequestDate, Long> {
}
