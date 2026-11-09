package com.ssafy.attendanceservice.domain.attendance.repository;

import com.ssafy.attendanceservice.domain.attendance.entity.WorkGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WorkGroupRepository extends JpaRepository<WorkGroup, Long> {

    Optional<WorkGroup> findByIsDefaultTrue();
}