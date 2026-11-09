package com.ssafy.attendanceservice.domain.attendance.service;

import com.ssafy.attendanceservice.domain.attendance.dto.AdminWorkStatusLogRequest;
import com.ssafy.attendanceservice.domain.attendance.dto.AdminWorkStatusLogUpdateRequest;
import com.ssafy.attendanceservice.domain.attendance.dto.DailyAttendanceResponse;
import com.ssafy.attendanceservice.domain.attendance.entity.AttendanceDaily;
import com.ssafy.attendanceservice.domain.attendance.entity.WorkGroup;
import com.ssafy.attendanceservice.domain.attendance.entity.WorkStatusCode;
import com.ssafy.attendanceservice.domain.attendance.entity.WorkStatusLog;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.WorkStatusSource;
import com.ssafy.attendanceservice.domain.attendance.repository.AttendanceDailyRepository;
import com.ssafy.attendanceservice.domain.attendance.repository.WorkStatusCodeRepository;
import com.ssafy.attendanceservice.domain.attendance.repository.WorkStatusLogRepository;
import com.ssafy.attendanceservice.global.exception.BusinessException;
import com.ssafy.attendanceservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class AdminAttendanceServiceImpl implements AdminAttendanceService {

    private final AttendanceDailyRepository attendanceDailyRepository;
    private final WorkStatusLogRepository workStatusLogRepository;
    private final WorkStatusCodeRepository workStatusCodeRepository;
    private final AttendanceDailyProcessor processor;
    private final AttendanceService attendanceService;

    @Override
    @Transactional
    public DailyAttendanceResponse registerLog(Long adminId, AdminWorkStatusLogRequest request) {
        Long empId = request.empId();
        LocalDate workDate = request.workDate();

        // 1. 상태코드 검증 (퇴근취소는 시스템 기록이라 직접 등록 불가)
        WorkStatusCode statusCode = getRegistrableStatusCode(request.statusCode());

        // 2. 시각은 근무일 당일 또는 다음 날 (야간 근무 퇴근)
        processor.validateStartAt(workDate, request.startAt());

        // 3. 출근/퇴근은 하루에 하나 (고치려면 수정 API 사용)
        if (isClockCode(statusCode)) {
            validateNoDuplicateClockLog(empId, workDate, statusCode);
        }

        // 4. 그날 근태가 없으면 생성
        AttendanceDaily daily = attendanceDailyRepository.findByEmpIdAndWorkDate(empId, workDate)
                .orElseGet(() -> createDaily(empId, workDate));

        // 5. 로그 저장
        saveAdminLog(adminId, empId, workDate, statusCode, request.startAt(), request.reason());

        // 6. 로그 기준으로 그날 근태 재동기화
        processor.syncDaily(daily);

        return attendanceService.getDaily(empId, workDate);
    }

    @Override
    @Transactional
    public DailyAttendanceResponse updateLog(Long adminId, Long logId, AdminWorkStatusLogUpdateRequest request) {
        // 1~2. 수정 대상 (삭제된 로그, 퇴근취소 로그는 수정 불가)
        WorkStatusLog origin = getEditableLog(logId);
        Long empId = origin.getEmpId();
        LocalDate workDate = origin.getWorkDate();

        // 3. 변경할 상태코드, 시각 검증
        WorkStatusCode statusCode = getRegistrableStatusCode(request.statusCode());
        processor.validateStartAt(workDate, request.startAt());

        // 4. 다른 상태를 출근/퇴근으로 바꾸는 경우 중복 확인 (같은 출근/퇴근의 시각만 고치는 건 허용)
        if (isClockCode(statusCode)
                && !statusCode.getStatusCode().equals(origin.getWorkStatusCode().getStatusCode())) {
            validateNoDuplicateClockLog(empId, workDate, statusCode);
        }

        // 5. 기존 로그는 삭제 처리, 수정 내용으로 새 로그 등록 (변경 이력 보존)
        origin.delete();
        saveAdminLog(adminId, empId, workDate, statusCode, request.startAt(), request.reason());

        // 6. 로그 기준으로 그날 근태 재동기화
        AttendanceDaily daily = attendanceDailyRepository.findByEmpIdAndWorkDate(empId, workDate)
                .orElseGet(() -> createDaily(empId, workDate));
        processor.syncDaily(daily);

        return attendanceService.getDaily(empId, workDate);
    }

    @Override
    @Transactional
    public DailyAttendanceResponse deleteLog(Long logId) {
        // 1. 삭제 대상 (이미 삭제된 로그, 퇴근취소 로그는 삭제 불가)
        WorkStatusLog log = getEditableLog(logId);
        Long empId = log.getEmpId();
        LocalDate workDate = log.getWorkDate();

        // 2. 소프트 삭제 (변경 이력에 남음)
        log.delete();

        // 3. 로그 기준으로 그날 근태 재동기화
        attendanceDailyRepository.findByEmpIdAndWorkDate(empId, workDate)
                .ifPresent(processor::syncDaily);

        return attendanceService.getDaily(empId, workDate);
    }

    /** 수정/삭제 가능한 로그. 없거나 삭제된 로그는 404, 퇴근취소(시스템 기록)는 409 */
    private WorkStatusLog getEditableLog(Long logId) {
        WorkStatusLog log = workStatusLogRepository.findById(logId)
                .filter(l -> !l.getIsDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.WORK_STATUS_LOG_NOT_FOUND));
        if (WorkStatusCode.CLOCK_OUT_CANCEL.equals(log.getWorkStatusCode().getStatusCode())) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_CHANGE);
        }
        return log;
    }

    private void saveAdminLog(Long adminId, Long empId, LocalDate workDate, WorkStatusCode statusCode,
                              OffsetDateTime startAt, String reason) {
        workStatusLogRepository.save(WorkStatusLog.builder()
                .workStatusCode(statusCode)
                .empId(empId)
                .workDate(workDate)
                .startAt(startAt)
                .source(WorkStatusSource.ADMIN)
                .createdBy(adminId)
                .reason(reason)
                .build());
    }

    private void validateNoDuplicateClockLog(Long empId, LocalDate workDate, WorkStatusCode statusCode) {
        if (workStatusLogRepository.existsByEmpIdAndWorkDateAndWorkStatusCode_StatusCodeAndIsDeletedFalse(
                empId, workDate, statusCode.getStatusCode())) {
            throw new BusinessException(ErrorCode.DUPLICATE_CLOCK_LOG);
        }
    }

    /** 등록 가능한 상태코드. 없는 코드이거나 퇴근취소면 INVALID_STATUS_CHANGE */
    private WorkStatusCode getRegistrableStatusCode(String statusCode) {
        if (WorkStatusCode.CLOCK_OUT_CANCEL.equals(statusCode)) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_CHANGE);
        }
        return workStatusCodeRepository.findById(statusCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_STATUS_CHANGE));
    }

    private boolean isClockCode(WorkStatusCode statusCode) {
        return WorkStatusCode.CLOCK_IN.equals(statusCode.getStatusCode())
                || WorkStatusCode.CLOCK_OUT.equals(statusCode.getStatusCode());
    }

    /** 출근 기록 없이 관리자가 먼저 등록하는 날. 출근 시각은 재동기화 때 로그에서 채운다. */
    private AttendanceDaily createDaily(Long empId, LocalDate workDate) {
        WorkGroup workGroup = processor.resolveWorkGroup(empId, workDate);
        return attendanceDailyRepository.save(AttendanceDaily.builder()
                .workGroup(workGroup)
                .empId(empId)
                .workDate(workDate)
                .clockIn(null)
                .isHoliday(processor.isHoliday(empId, workDate, workGroup))
                .build());
    }
}