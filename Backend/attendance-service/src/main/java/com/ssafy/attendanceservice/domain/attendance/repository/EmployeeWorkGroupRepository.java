package com.ssafy.attendanceservice.domain.attendance.repository;

import com.ssafy.attendanceservice.domain.attendance.entity.EmployeeWorkGroup;
import com.ssafy.attendanceservice.domain.attendance.entity.WorkGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface EmployeeWorkGroupRepository extends JpaRepository<EmployeeWorkGroup, Long> {

    // 해당 날짜에 직원에게 적용되는 근무그룹
    @Query("""
            select wg from EmployeeWorkGroup ewg
            join ewg.workGroup wg
            where ewg.empId = :empId
              and ewg.startDate <= :date
              and (ewg.endDate is null or ewg.endDate >= :date)
            """)
    Optional<WorkGroup> findEffectiveWorkGroup(@Param("empId") Long empId, @Param("date") LocalDate date);
}