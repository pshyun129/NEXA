package com.ssafy.attendanceservice.domain.attendance.repository;

import com.ssafy.attendanceservice.domain.attendance.entity.AttendanceDaily;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AttendanceDailyRepository extends JpaRepository<AttendanceDaily, Long> {

    boolean existsByEmpIdAndWorkDate(Long empId, LocalDate workDate);

    // 가장 최근 근무일의 근태 (퇴근 처리 대상)
    Optional<AttendanceDaily> findTopByEmpIdOrderByWorkDateDesc(Long empId);

    // 특정 근무일의 근태
    Optional<AttendanceDaily> findByEmpIdAndWorkDate(Long empId, LocalDate workDate);

    // 기간 내 근태 (날짜순)
    List<AttendanceDaily> findByEmpIdAndWorkDateBetweenOrderByWorkDateAsc(Long empId, LocalDate from, LocalDate to);
}