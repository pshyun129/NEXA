package com.ssafy.attendanceservice.domain.attendance.service;

import com.ssafy.attendanceservice.domain.attendance.dto.ApprovedLeave;
import com.ssafy.attendanceservice.domain.attendance.entity.EmployeeLeave;
import com.ssafy.attendanceservice.domain.attendance.repository.EmployeeLeaveRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LeaveServiceImpl implements LeaveService {

    private final EmployeeLeaveRepository employeeLeaveRepository;
    private final AttendanceDailyProcessor processor;

    @Override
    @Transactional
    public void applyApprovedLeave(ApprovedLeave leave) {
        // 1. 이미 저장한 휴가면 무시 (중복 수신)
        if (!employeeLeaveRepository.findByLeaveReqId(leave.leaveReqId()).isEmpty()) {
            log.info("휴가 저장 무시 (이미 저장됨): leaveReqId={}", leave.leaveReqId());
            return;
        }

        // 2. 날짜별로 저장
        List<EmployeeLeave> leaves = leave.dates().stream()
                .map(day -> EmployeeLeave.builder()
                        .leaveReqId(leave.leaveReqId())
                        .empId(leave.empId())
                        .leaveDate(day.leaveDate())
                        .portion(day.portion())
                        .leaveTypeName(leave.leaveTypeName())
                        .isPaid(leave.isPaid())
                        .build())
                .toList();
        employeeLeaveRepository.saveAll(leaves);

        // 3. 이미 출퇴근한 날(지난 날짜 휴가 등)은 휴가 기준으로 다시 판정
        leaves.forEach(saved -> processor.resyncDay(saved.getEmpId(), saved.getLeaveDate()));
    }

    @Override
    @Transactional
    public void cancelLeave(Long leaveReqId) {
        List<EmployeeLeave> leaves = employeeLeaveRepository.findByLeaveReqId(leaveReqId);
        employeeLeaveRepository.deleteAll(leaves);
        leaves.forEach(canceled -> processor.resyncDay(canceled.getEmpId(), canceled.getLeaveDate()));
    }
}
