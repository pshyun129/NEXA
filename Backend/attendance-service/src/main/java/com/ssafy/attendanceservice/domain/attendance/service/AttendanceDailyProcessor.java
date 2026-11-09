package com.ssafy.attendanceservice.domain.attendance.service;

import com.ssafy.attendanceservice.domain.attendance.calculator.AnomalyJudge;
import com.ssafy.attendanceservice.domain.attendance.calculator.ApprovedOvertime;
import com.ssafy.attendanceservice.domain.attendance.calculator.WorkLogEntry;
import com.ssafy.attendanceservice.domain.attendance.calculator.WorkTimeCalculator;
import com.ssafy.attendanceservice.domain.attendance.calculator.WorkTimeResult;
import com.ssafy.attendanceservice.domain.attendance.entity.*;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AnomalyType;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AttendanceRequestStatus;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AttendanceRequestType;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.LeavePortion;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.WorkStatusSource;
import com.ssafy.attendanceservice.domain.attendance.repository.AttendanceAnomalyRepository;
import com.ssafy.attendanceservice.domain.attendance.repository.AttendanceDailyRepository;
import com.ssafy.attendanceservice.domain.attendance.repository.AttendanceRequestRepository;
import com.ssafy.attendanceservice.domain.attendance.repository.EmployeeLeaveRepository;
import com.ssafy.attendanceservice.domain.attendance.repository.EmployeeWorkGroupRepository;
import com.ssafy.attendanceservice.domain.attendance.repository.HolidayRepository;
import com.ssafy.attendanceservice.domain.attendance.repository.WorkGroupRepository;
import com.ssafy.attendanceservice.domain.attendance.repository.WorkStatusCodeRepository;
import com.ssafy.attendanceservice.domain.attendance.repository.WorkStatusLogRepository;
import com.ssafy.attendanceservice.global.exception.BusinessException;
import com.ssafy.attendanceservice.global.exception.ErrorCode;
import com.ssafy.attendanceservice.global.util.DateTimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 일일 근태 공통 처리. 본인 출퇴근, 관리자 로그 수정, 결재 반영이 같은 규칙으로 동작하도록 한 곳에 모은다.
 * 트랜잭션은 호출하는 서비스 메서드의 트랜잭션에 참여한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttendanceDailyProcessor {

    private final WorkStatusLogRepository workStatusLogRepository;
    private final WorkStatusCodeRepository workStatusCodeRepository;
    private final AttendanceAnomalyRepository attendanceAnomalyRepository;
    private final EmployeeWorkGroupRepository employeeWorkGroupRepository;
    private final WorkGroupRepository workGroupRepository;
    private final HolidayRepository holidayRepository;
    private final AttendanceRequestRepository attendanceRequestRepository;
    private final AttendanceDailyRepository attendanceDailyRepository;
    private final EmployeeLeaveRepository employeeLeaveRepository;

    // 휴일 판정에 반영하는 휴일대체 상태 (승인된 것만)
    private static final List<AttendanceRequestStatus> APPROVED_ONLY = List.of(AttendanceRequestStatus.APPROVED);

    /** 해당 날짜에 배정된 근무그룹. 배정이 없으면 기본 그룹 */
    public WorkGroup resolveWorkGroup(Long empId, LocalDate date) {
        return employeeWorkGroupRepository.findEffectiveWorkGroup(empId, date)
                .or(workGroupRepository::findByIsDefaultTrue)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEFAULT_WORK_GROUP_NOT_FOUND));
    }

    /**
     * 휴일 여부. 승인된 휴일대체가 우선이다: 근무한 휴일(targetDate)은 근무일, 대신 쉬는 날(substDate)은 휴일.
     * 그 외에는 공휴일이거나 근무그룹의 근무 요일이 아니면 휴일
     */
    public boolean isHoliday(Long empId, LocalDate date, WorkGroup workGroup) {
        if (attendanceRequestRepository.existsByEmpIdAndReqTypeAndTargetDateAndStatusIn(
                empId, AttendanceRequestType.HOLIDAY_SUBST, date, APPROVED_ONLY)) {
            return false;
        }
        if (attendanceRequestRepository.existsByEmpIdAndReqTypeAndSubstDateAndStatusIn(
                empId, AttendanceRequestType.HOLIDAY_SUBST, date, APPROVED_ONLY)) {
            return true;
        }
        return holidayRepository.existsById(date) || !workGroup.isWorkDay(date.getDayOfWeek());
    }

    /** 로그 시각은 근무일 당일 또는 다음 날(야간 근무 퇴근)만 허용 */
    public void validateStartAt(LocalDate workDate, OffsetDateTime startAt) {
        if (startAt == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "startAt은 필수입니다.");
        }
        LocalDate startDate = startAt.atZoneSameInstant(DateTimeUtils.ZONE).toLocalDate();
        if (!startDate.equals(workDate) && !startDate.equals(workDate.plusDays(1))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "startAt은 근무일 당일 또는 다음 날이어야 합니다.");
        }
    }

    public WorkStatusCode getStatusCode(String statusCode) {
        return workStatusCodeRepository.findById(statusCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.WORK_STATUS_CODE_NOT_FOUND));
    }

    /** 그 근무일에 승인된 초과근무 신청의 시간 구간 (재계산과 근무시간 수정 미리보기가 같이 쓴다) */
    public List<ApprovedOvertime> approvedOvertimes(Long empId, LocalDate workDate) {
        return attendanceRequestRepository.findByEmpIdAndTargetDateAndReqTypeAndStatus(
                        empId, workDate, AttendanceRequestType.OVERTIME, AttendanceRequestStatus.APPROVED).stream()
                .flatMap(request -> request.getItems().stream())
                .filter(item -> item.getEndAt() != null)
                .map(item -> new ApprovedOvertime(item.getStartAt(), item.getEndAt()))
                .toList();
    }

    /** 그 근무일의 로그로 근로시간을 다시 계산해서 반영 (퇴근 전이면 0) */
    public WorkTimeResult recalculate(AttendanceDaily daily) {
        List<WorkLogEntry> entries = workStatusLogRepository
                .findByEmpIdAndWorkDateAndIsDeletedFalseOrderByStartAtAscLogIdAsc(daily.getEmpId(), daily.getWorkDate())
                .stream()
                .map(log -> new WorkLogEntry(log.getStartAt(), log.getWorkStatusCode().getCountAsWork()))
                .toList();

        WorkGroup workGroup = daily.getWorkGroup();
        WorkTimeResult result = WorkTimeCalculator.calculate(
                entries,
                daily.getClockOut(),
                workGroup.getDailyStdMinutes(),
                workGroup.getBreakMinutes(),
                daily.getIsHoliday(),
                approvedOvertimes(daily.getEmpId(), daily.getWorkDate()));
        daily.applyWorkTime(result);
        return result;
    }

    /** 퇴근 판정 근태이상 갱신: 기존 판정분을 지우고, 퇴근 상태면 다시 판정 */
    public void refreshClockOutAnomalies(AttendanceDaily daily, WorkTimeResult workTime) {
        attendanceAnomalyRepository.deleteByAttendanceDailyAndAnomalyTypeIn(daily, AnomalyJudge.clockOutTypes());
        if (!daily.isClockedOut()) {
            return;
        }
        DaySchedule schedule = scheduleOf(daily.getEmpId(), daily.getWorkDate(), daily.getWorkGroup());
        saveAnomalies(daily, AnomalyJudge.onClockOut(
                daily.getWorkDate(),
                daily.getClockOut(),
                schedule.endTime(),
                schedule.requiredMin(),
                daily.getIsHoliday(),
                workTime));
    }

    public void saveAnomalies(AttendanceDaily daily, List<AnomalyType> types) {
        types.forEach(type -> attendanceAnomalyRepository.save(AttendanceAnomaly.builder()
                .attendanceDaily(daily)
                .anomalyType(type)
                .build()));
    }

    /**
     * 로그 기준으로 그날의 근태 전체를 다시 맞춘다 (관리자 수정, 결재 반영 후).
     * 출퇴근 시각 → 근로시간 → 근태이상(지각, 퇴근 판정, 출퇴근 누락) 순서로 재계산한다.
     */
    public void syncDaily(AttendanceDaily daily) {
        List<WorkStatusLog> logs = workStatusLogRepository
                .findByEmpIdAndWorkDateAndIsDeletedFalseOrderByStartAtAscLogIdAsc(daily.getEmpId(), daily.getWorkDate());

        // 1. 출퇴근 시각: 출근은 첫 출근 로그, 퇴근은 마지막 퇴근 로그
        OffsetDateTime clockIn = logs.stream()
                .filter(log -> WorkStatusCode.CLOCK_IN.equals(log.getWorkStatusCode().getStatusCode()))
                .map(WorkStatusLog::getStartAt)
                .findFirst()
                .orElse(null);
        OffsetDateTime clockOut = logs.stream()
                .filter(log -> WorkStatusCode.CLOCK_OUT.equals(log.getWorkStatusCode().getStatusCode()))
                .map(WorkStatusLog::getStartAt)
                .reduce((first, second) -> second)
                .orElse(null);
        daily.syncClock(clockIn, clockOut);

        // 2. 근로시간
        WorkTimeResult workTime = recalculate(daily);

        // 3. 지각: 기존 판정을 지우고 출근 시각으로 다시 판정 (오전 반차면 반차 후 출근 기준)
        attendanceAnomalyRepository.deleteByAttendanceDailyAndAnomalyTypeIn(daily, List.of(AnomalyType.LATE));
        if (clockIn != null) {
            DaySchedule schedule = scheduleOf(daily.getEmpId(), daily.getWorkDate(), daily.getWorkGroup());
            saveAnomalies(daily, AnomalyJudge.onClockIn(
                    daily.getWorkDate(), clockIn, schedule.startTime(), daily.getIsHoliday()));
        }

        // 4. 출퇴근 누락: 퇴근이 생겼으면 해소
        if (clockOut != null) {
            attendanceAnomalyRepository.deleteByAttendanceDailyAndAnomalyTypeIn(daily, List.of(AnomalyType.MISSING_CLOCK));
        }

        // 5. 조퇴, 소정근로 미달, 미승인 초과근로
        refreshClockOutAnomalies(daily, workTime);
    }

    /**
     * 그날 근태 기록의 휴일 여부를 다시 판정하고 근태를 재계산한다 (휴일대체 승인 후).
     * 기록이 없는 날은 할 일이 없다 (조회 시 isHoliday로 판정)
     */
    public void resyncHoliday(Long empId, LocalDate date) {
        attendanceDailyRepository.findByEmpIdAndWorkDate(empId, date).ifPresent(daily -> {
            daily.changeHoliday(isHoliday(empId, date, daily.getWorkGroup()));
            syncDaily(daily);
        });
    }

    /** 그날 근태 기록이 있으면 다시 판정한다 (휴가 저장·취소 후). 기록이 없는 날은 할 일이 없다 */
    public void resyncDay(Long empId, LocalDate date) {
        attendanceDailyRepository.findByEmpIdAndWorkDate(empId, date).ifPresent(this::syncDaily);
    }

    // ===== 휴가 =====

    // 오전 반차(09~13) 뒤 점심(13~14)을 지나 출근하는 시각, 오후 반차(14~18) 전 점심 전에 퇴근하는 시각
    private static final LocalTime AM_HALF_WORK_START = LocalTime.of(14, 0);
    private static final LocalTime PM_HALF_WORK_END = LocalTime.of(13, 0);

    /**
     * 그날 근태이상 판정 기준.
     * 반차면 출근·퇴근 기준을 반차에 맞춰 옮기고, 소정근로 미달은 판정하지 않는다
     * (반차 날 4시간 근무는 휴게 30분이 빠져 3시간 30분으로 계산되므로)
     *
     * @param requiredMin 소정근로 미달 기준(분). 0이면 판정하지 않음
     */
    public record DaySchedule(LocalTime startTime, LocalTime endTime, int requiredMin) {
    }

    public DaySchedule scheduleOf(Long empId, LocalDate date, WorkGroup workGroup) {
        List<LeavePortion> portions = leavePortions(empId, date);
        return new DaySchedule(
                portions.contains(LeavePortion.AM_HALF) ? AM_HALF_WORK_START : workGroup.getStartTime(),
                portions.contains(LeavePortion.PM_HALF) ? PM_HALF_WORK_END : workGroup.getEndTime(),
                portions.isEmpty() ? workGroup.getDailyStdMinutes() : 0);
    }

    /** 그날 휴가 구분. 휴가가 없으면 빈 목록 */
    public List<LeavePortion> leavePortions(Long empId, LocalDate date) {
        return employeeLeaveRepository.findByEmpIdAndLeaveDate(empId, date).stream()
                .map(EmployeeLeave::getPortion)
                .toList();
    }

    /** 종일 휴가인가 (오전·오후 반차를 따로 쓴 날도 종일) */
    public boolean isFullDayLeave(Long empId, LocalDate date) {
        List<LeavePortion> portions = leavePortions(empId, date);
        return portions.contains(LeavePortion.FULL)
                || (portions.contains(LeavePortion.AM_HALF) && portions.contains(LeavePortion.PM_HALF));
    }

    /**
     * 승인된 근무시간 수정 신청을 상태 로그에 적용하고 그날을 재계산한다.
     * 신청 후 대상 로그가 바뀌었거나 출퇴근이 겹치는 항목은 건너뛴다 (경고 로그)
     */
    public void applyTimeFix(AttendanceRequest request) {
        Long empId = request.getEmpId();
        LocalDate workDate = request.getTargetDate();

        for (AttendanceRequestItem item : request.getItems()) {
            switch (item.getAction()) {
                case ADD -> {
                    if (hasOtherClockLog(empId, workDate, item.getWorkStatusCode(), null)) {
                        skip(request, item, "같은 출근/퇴근 기록이 이미 있음");
                        continue;
                    }
                    saveApprovalLog(request, item.getWorkStatusCode(), item.getStartAt());
                }
                case MODIFY -> {
                    WorkStatusLog target = findTargetLog(empId, workDate, item.getTargetLogId());
                    if (target == null) {
                        skip(request, item, "대상 로그가 없거나 바뀜");
                        continue;
                    }
                    if (hasOtherClockLog(empId, workDate, item.getWorkStatusCode(), target)) {
                        skip(request, item, "같은 출근/퇴근 기록이 이미 있음");
                        continue;
                    }
                    target.delete();
                    saveApprovalLog(request, item.getWorkStatusCode(), item.getStartAt());
                }
                case DELETE -> {
                    WorkStatusLog target = findTargetLog(empId, workDate, item.getTargetLogId());
                    if (target == null) {
                        skip(request, item, "대상 로그가 없거나 이미 삭제됨");
                        continue;
                    }
                    target.delete();
                }
            }
        }

        // 그날 근태가 없으면 만들고 로그 기준으로 재계산
        syncDaily(getOrCreateDaily(empId, workDate));
    }

    /** 수정·삭제 대상: 그 사원·그 날짜의 삭제되지 않은 로그 (퇴근취소 제외). 아니면 null */
    private WorkStatusLog findTargetLog(Long empId, LocalDate workDate, Long logId) {
        if (logId == null) {
            return null;
        }
        return workStatusLogRepository.findById(logId)
                .filter(log -> !log.getIsDeleted())
                .filter(log -> log.getEmpId().equals(empId) && log.getWorkDate().equals(workDate))
                .filter(log -> !WorkStatusCode.CLOCK_OUT_CANCEL.equals(log.getWorkStatusCode().getStatusCode()))
                .orElse(null);
    }

    /** 출근/퇴근은 하루에 하나: 바꿀 대상(target)을 빼고 같은 출근/퇴근 로그가 이미 있는가 */
    private boolean hasOtherClockLog(Long empId, LocalDate workDate, WorkStatusCode statusCode, WorkStatusLog target) {
        String code = statusCode.getStatusCode();
        if (!WorkStatusCode.CLOCK_IN.equals(code) && !WorkStatusCode.CLOCK_OUT.equals(code)) {
            return false;
        }
        boolean targetIsSameCode = target != null && code.equals(target.getWorkStatusCode().getStatusCode());
        return !targetIsSameCode && workStatusLogRepository
                .existsByEmpIdAndWorkDateAndWorkStatusCode_StatusCodeAndIsDeletedFalse(empId, workDate, code);
    }

    private void saveApprovalLog(AttendanceRequest request, WorkStatusCode statusCode, OffsetDateTime startAt) {
        workStatusLogRepository.save(WorkStatusLog.builder()
                .workStatusCode(statusCode)
                .empId(request.getEmpId())
                .workDate(request.getTargetDate())
                .startAt(startAt)
                .source(WorkStatusSource.APPROVAL)
                .createdBy(request.getEmpId())
                .reason(request.getReason())
                .build());
    }

    private void skip(AttendanceRequest request, AttendanceRequestItem item, String why) {
        log.warn("근무시간 수정 항목 건너뜀: attReqId={}, itemId={}, action={}, targetLogId={}, 이유={}",
                request.getAttReqId(), item.getItemId(), item.getAction(), item.getTargetLogId(), why);
    }

    /** 그날 근태 기록. 없으면 그날 근무그룹과 휴일 여부로 만든다 */
    private AttendanceDaily getOrCreateDaily(Long empId, LocalDate workDate) {
        return attendanceDailyRepository.findByEmpIdAndWorkDate(empId, workDate).orElseGet(() -> {
            WorkGroup workGroup = resolveWorkGroup(empId, workDate);
            return attendanceDailyRepository.save(AttendanceDaily.builder()
                    .workGroup(workGroup)
                    .empId(empId)
                    .workDate(workDate)
                    .clockIn(null)
                    .isHoliday(isHoliday(empId, workDate, workGroup))
                    .build());
        });
    }
}
