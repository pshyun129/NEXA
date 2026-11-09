package com.ssafy.attendanceservice.domain.attendance.repository;

import com.ssafy.attendanceservice.domain.attendance.entity.AttendanceAnomaly;
import com.ssafy.attendanceservice.domain.attendance.entity.AttendanceDaily;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AnomalyType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AttendanceAnomalyRepository extends JpaRepository<AttendanceAnomaly, Long> {

    // 특정 유형의 근태이상 삭제 (퇴근취소/재퇴근 시 퇴근 판정분 재판정용)
    void deleteByAttendanceDailyAndAnomalyTypeIn(AttendanceDaily attendanceDaily, Collection<AnomalyType> anomalyTypes);

    // 일일 근태의 근태이상 목록 (판정 순)
    List<AttendanceAnomaly> findByAttendanceDailyOrderByAnomalyIdAsc(AttendanceDaily attendanceDaily);

    // 여러 일일 근태의 근태이상 (판정 순)
    List<AttendanceAnomaly> findByAttendanceDailyInOrderByAnomalyIdAsc(Collection<AttendanceDaily> attendanceDailies);
}