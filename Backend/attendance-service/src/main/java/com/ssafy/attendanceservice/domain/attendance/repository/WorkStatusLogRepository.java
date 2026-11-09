package com.ssafy.attendanceservice.domain.attendance.repository;

import com.ssafy.attendanceservice.domain.attendance.entity.WorkStatusLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WorkStatusLogRepository extends JpaRepository<WorkStatusLog, Long> {

    // 해당 근무일의 마지막 상태 로그 (= 현재 상태)
    Optional<WorkStatusLog> findTopByEmpIdAndWorkDateAndIsDeletedFalseOrderByStartAtDescLogIdDesc(
            Long empId, LocalDate workDate);

    // 해당 근무일의 상태 로그 전체 (시간순, 근로시간 계산용)
    List<WorkStatusLog> findByEmpIdAndWorkDateAndIsDeletedFalseOrderByStartAtAscLogIdAsc(
            Long empId, LocalDate workDate);

    // 기간 내 상태 로그 전체 (삭제 포함, 변경 이력 조회용)
    List<WorkStatusLog> findByEmpIdAndWorkDateBetweenOrderByWorkDateAscStartAtAscLogIdAsc(
            Long empId, LocalDate from, LocalDate to);

    // 해당 근무일에 특정 상태(출근/퇴근) 로그가 있는지 (삭제 제외)
    boolean existsByEmpIdAndWorkDateAndWorkStatusCode_StatusCodeAndIsDeletedFalse(
            Long empId, LocalDate workDate, String statusCode);
}