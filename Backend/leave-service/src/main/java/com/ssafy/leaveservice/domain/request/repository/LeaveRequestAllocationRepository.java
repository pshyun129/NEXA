package com.ssafy.leaveservice.domain.request.repository;

import com.ssafy.leaveservice.domain.request.entity.LeaveRequestAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LeaveRequestAllocationRepository extends JpaRepository<LeaveRequestAllocation, Long> {

    List<LeaveRequestAllocation> findAllByRequestIdOrderByAllocationIdAsc(Long requestId);
}
