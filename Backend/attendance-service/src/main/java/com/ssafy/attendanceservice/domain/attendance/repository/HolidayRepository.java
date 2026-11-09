package com.ssafy.attendanceservice.domain.attendance.repository;

import com.ssafy.attendanceservice.domain.attendance.entity.Holiday;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface HolidayRepository extends JpaRepository<Holiday, LocalDate> {

    List<Holiday> findByHolidayDateBetweenOrderByHolidayDateAsc(LocalDate startDate, LocalDate endDate);
}