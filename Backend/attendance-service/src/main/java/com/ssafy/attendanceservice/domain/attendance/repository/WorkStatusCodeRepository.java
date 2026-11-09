package com.ssafy.attendanceservice.domain.attendance.repository;

import com.ssafy.attendanceservice.domain.attendance.entity.WorkStatusCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface WorkStatusCodeRepository extends JpaRepository<WorkStatusCode, String> {

    List<WorkStatusCode> findByStatusCodeNotIn(Collection<String> statusCodes);
}