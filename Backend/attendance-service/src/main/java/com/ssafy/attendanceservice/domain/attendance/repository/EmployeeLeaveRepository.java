package com.ssafy.attendanceservice.domain.attendance.repository;

import com.ssafy.attendanceservice.domain.attendance.entity.EmployeeLeave;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface EmployeeLeaveRepository extends JpaRepository<EmployeeLeave, Long> {

    // 그날 휴가 (오전·오후 반차를 따로 신청하면 두 건)
    List<EmployeeLeave> findByEmpIdAndLeaveDate(Long empId, LocalDate leaveDate);

    // 기간 내 휴가 (주간·월간 조회, 월 집계)
    List<EmployeeLeave> findByEmpIdAndLeaveDateBetween(Long empId, LocalDate from, LocalDate to);

    // 휴가 신청 한 건의 날짜별 휴가 (중복 수신 확인, 취소)
    List<EmployeeLeave> findByLeaveReqId(Long leaveReqId);
}
