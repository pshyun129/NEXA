package com.ssafy.attendanceservice.domain.attendance.service;

import com.ssafy.attendanceservice.domain.attendance.calculator.AnomalyJudge;
import com.ssafy.attendanceservice.domain.attendance.calculator.WorkTimeResult;
import com.ssafy.attendanceservice.domain.attendance.dto.*;
import com.ssafy.attendanceservice.domain.attendance.entity.*;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AnomalyType;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AttendanceRequestStatus;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AttendanceRequestType;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.WorkStatusSource;
import com.ssafy.attendanceservice.domain.attendance.repository.*;
import com.ssafy.attendanceservice.global.exception.BusinessException;
import com.ssafy.attendanceservice.global.exception.ErrorCode;
import com.ssafy.attendanceservice.global.util.DateTimeUtils;
import com.ssafy.nexa.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class AttendanceServiceImpl implements AttendanceService {

    // 퇴근 후 퇴근취소(상태변경)를 허용하는 시간
    private static final Duration CLOCK_OUT_CANCEL_LIMIT = Duration.ofHours(2);

    // 상태변경 목록에서 제외하는 상태: 버튼(출근/퇴근)으로만 처리하는 상태, 휴가 결재로만 반영하는 휴가
    private static final List<String> NOT_SELECTABLE_CODES = List.of(
            WorkStatusCode.CLOCK_IN,
            WorkStatusCode.CLOCK_OUT,
            WorkStatusCode.CLOCK_OUT_CANCEL,
            WorkStatusCode.LEAVE
    );

    // 변경 이력 최대 조회 기간 (시작일·종료일 포함)
    private static final int HISTORY_MAX_DAYS = 31;

    private final AttendanceDailyRepository attendanceDailyRepository;
    private final WorkStatusLogRepository workStatusLogRepository;
    private final WorkStatusCodeRepository workStatusCodeRepository;
    private final AttendanceAnomalyRepository attendanceAnomalyRepository;
    private final HolidayRepository holidayRepository;
    private final EmployeeLeaveRepository employeeLeaveRepository;
    private final AttendanceRequestRepository attendanceRequestRepository;
    private final AttendanceDailyProcessor processor;
    private final AttendanceViewScope attendanceViewScope;

    @Override
    @Transactional
    public AttendanceStatusResponse clockIn(Long empId) {
        OffsetDateTime now = DateTimeUtils.now();
        LocalDate today = now.toLocalDate();

        // 1. 출근은 하루 1번
        if (attendanceDailyRepository.existsByEmpIdAndWorkDate(empId, today)) {
            throw new BusinessException(ErrorCode.ALREADY_CLOCKED_IN);
        }

        // 2. 종일 휴가 날은 출근 불가 (일해야 하면 휴가를 취소한 뒤 출근)
        if (processor.isFullDayLeave(empId, today)) {
            throw new BusinessException(ErrorCode.LEAVE_DAY_CLOCK_IN);
        }

        // 3. 직전 근무에 퇴근이 없으면 출퇴근 누락 (오늘 기록을 만들기 전에 판정)
        attendanceDailyRepository.findTopByEmpIdOrderByWorkDateDesc(empId)
                .filter(previous -> !previous.isClockedOut())
                .ifPresent(previous -> processor.saveAnomalies(previous, List.of(AnomalyType.MISSING_CLOCK)));

        // 4. 적용 근무그룹, 휴일 여부
        WorkGroup workGroup = processor.resolveWorkGroup(empId, today);
        boolean isHoliday = processor.isHoliday(empId, today, workGroup);

        // 5. 일일 근태 생성
        AttendanceDaily daily = attendanceDailyRepository.save(AttendanceDaily.builder()
                .workGroup(workGroup)
                .empId(empId)
                .workDate(today)
                .clockIn(now)
                .isHoliday(isHoliday)
                .build());

        // 6. 지각 판정 (오전 반차면 반차 후 출근 기준)
        LocalTime startTime = processor.scheduleOf(empId, today, workGroup).startTime();
        processor.saveAnomalies(daily, AnomalyJudge.onClockIn(today, now, startTime, isHoliday));

        // 7. 출근 로그
        WorkStatusCode clockInCode = processor.getStatusCode(WorkStatusCode.CLOCK_IN);
        workStatusLogRepository.save(WorkStatusLog.builder()
                .workStatusCode(clockInCode)
                .empId(empId)
                .workDate(today)
                .startAt(now)
                .source(WorkStatusSource.SELF)
                .createdBy(empId)
                .build());

        return AttendanceStatusResponse.of(daily, clockInCode, getAbleStatuses(clockInCode));
    }

    @Override
    @Transactional
    public AttendanceStatusResponse clockOut(Long empId) {
        OffsetDateTime now = DateTimeUtils.now();

        // 1. 가장 최근 근태 (자정을 넘긴 퇴근도 출근일 기록에 처리)
        AttendanceDaily daily = attendanceDailyRepository.findTopByEmpIdOrderByWorkDateDesc(empId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_CLOCKED_IN));

        // 2. 퇴근 취소 없이 다시 퇴근할 수 없음
        if (daily.isClockedOut()) {
            throw new BusinessException(ErrorCode.ALREADY_CLOCKED_OUT);
        }

        // 3. 퇴근 시각 기록 (변경 감지로 UPDATE)
        daily.clockOut(now);

        // 4. 퇴근 로그
        WorkStatusCode clockOutCode = processor.getStatusCode(WorkStatusCode.CLOCK_OUT);
        workStatusLogRepository.save(WorkStatusLog.builder()
                .workStatusCode(clockOutCode)
                .empId(empId)
                .workDate(daily.getWorkDate())
                .startAt(now)
                .source(WorkStatusSource.SELF)
                .createdBy(empId)
                .build());

        // 5. 근로시간 집계 + 근태이상 판정
        WorkTimeResult workTime = processor.recalculate(daily);
        processor.refreshClockOutAnomalies(daily, workTime);

        return AttendanceStatusResponse.of(daily, clockOutCode, getAbleStatuses(clockOutCode));
    }

    @Override
    @Transactional
    public AttendanceStatusResponse changeStatus(Long empId, String statusCode) {
        OffsetDateTime now = DateTimeUtils.now();

        // 1. 가장 최근 근태와 현재 상태
        AttendanceDaily daily = attendanceDailyRepository.findTopByEmpIdOrderByWorkDateDesc(empId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_CLOCKED_IN));
        WorkStatusLog lastLog = workStatusLogRepository
                .findTopByEmpIdAndWorkDateAndIsDeletedFalseOrderByStartAtDescLogIdDesc(empId, daily.getWorkDate())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_CLOCKED_IN));
        WorkStatusCode current = lastLog.getWorkStatusCode();

        // 2. 퇴근 후 2시간이 지나면 변경 불가 (이후는 근무시간 수정 신청으로 처리)
        if (daily.isClockedOut() && now.isAfter(daily.getClockOut().plus(CLOCK_OUT_CANCEL_LIMIT))) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_CHANGE);
        }

        // 3. 요청한 상태가 지금 바꿀 수 있는 상태인지
        WorkStatusCode target = getAbleStatuses(current).stream()
                .filter(code -> code.getStatusCode().equals(statusCode))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_STATUS_CHANGE));

        // 4. 퇴근 상태였다면 퇴근취소 (집계 초기화, 퇴근 판정 근태이상 삭제)
        if (daily.isClockedOut()) {
            lastLog.changeWorkStatusCode(processor.getStatusCode(WorkStatusCode.CLOCK_OUT_CANCEL));
            daily.cancelClockOut();
            WorkTimeResult workTime = processor.recalculate(daily);
            processor.refreshClockOutAnomalies(daily, workTime);
        }

        // 5. 상태 로그
        workStatusLogRepository.save(WorkStatusLog.builder()
                .workStatusCode(target)
                .empId(empId)
                .workDate(daily.getWorkDate())
                .startAt(now)
                .source(WorkStatusSource.SELF)
                .createdBy(empId)
                .build());

        return AttendanceStatusResponse.of(daily, target, getAbleStatuses(target));
    }

    @Override
    @Transactional(readOnly = true)
    public AttendanceStatusResponse getToday(Long empId) {
        OffsetDateTime now = DateTimeUtils.now();
        LocalDate today = now.toLocalDate();

        // 1. 근태 기록이 없으면 출근 전
        Optional<AttendanceDaily> latest = attendanceDailyRepository.findTopByEmpIdOrderByWorkDateDesc(empId);
        if (latest.isEmpty()) {
            return AttendanceStatusResponse.beforeClockIn(today);
        }

        AttendanceDaily daily = latest.get();
        WorkStatusCode current = workStatusLogRepository
                .findTopByEmpIdAndWorkDateAndIsDeletedFalseOrderByStartAtDescLogIdDesc(empId, daily.getWorkDate())
                .map(WorkStatusLog::getWorkStatusCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_CLOCKED_IN));

        // 2. 퇴근 후 2시간이 지난 경우
        if (daily.isClockedOut() && now.isAfter(daily.getClockOut().plus(CLOCK_OUT_CANCEL_LIMIT))) {
            // 오늘 근무: 다시 출근할 수 없으니 퇴근 상태로 보여주고 변경 불가
            if (daily.getWorkDate().equals(today)) {
                return AttendanceStatusResponse.of(daily, current, List.of());
            }
            // 이전 근무: 새 근무일 시작 전
            return AttendanceStatusResponse.beforeClockIn(today);
        }

        // 3. 근무 중이거나 퇴근 후 2시간 이내
        return AttendanceStatusResponse.of(daily, current, getAbleStatuses(current));
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkStatusCodeResponse> getStatusCodes() {
        return workStatusCodeRepository.findAll(Sort.by("statusCode")).stream()
                .map(WorkStatusCodeResponse::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<HolidayResponse> getHolidays(Long empId, LocalDate startDate, LocalDate endDate) {
        if (startDate.isAfter(endDate)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "startDate는 endDate보다 늦을 수 없습니다.");
        }

        // 1. 승인된 휴일대체: 근무한 휴일(targetDate)은 근무일이 되고, 대체일(substDate)은 쉬는 날
        Set<LocalDate> workedHolidays = attendanceRequestRepository
                .findByEmpIdAndReqTypeAndStatusAndTargetDateBetween(empId, AttendanceRequestType.HOLIDAY_SUBST,
                        AttendanceRequestStatus.APPROVED, startDate, endDate).stream()
                .map(AttendanceRequest::getTargetDate)
                .collect(Collectors.toSet());
        Stream<HolidayResponse> substitutes = attendanceRequestRepository
                .findByEmpIdAndReqTypeAndStatusAndSubstDateBetween(empId, AttendanceRequestType.HOLIDAY_SUBST,
                        AttendanceRequestStatus.APPROVED, startDate, endDate).stream()
                .map(request -> HolidayResponse.substitute(request.getSubstDate()));

        // 2. 공휴일 중 근무일이 된 날을 빼고 대체휴일을 더한다
        Stream<HolidayResponse> holidays = holidayRepository
                .findByHolidayDateBetweenOrderByHolidayDateAsc(startDate, endDate).stream()
                .filter(holiday -> !workedHolidays.contains(holiday.getHolidayDate()))
                .map(HolidayResponse::from);

        return Stream.concat(holidays, substitutes)
                .sorted(Comparator.comparing(HolidayResponse::holidayDate))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public DailyAttendanceResponse getDaily(Long empId, LocalDate date) {
        return attendanceDailyRepository.findByEmpIdAndWorkDate(empId, date)
                .map(daily -> DailyAttendanceResponse.of(
                        daily,
                        workStatusLogRepository.findByEmpIdAndWorkDateAndIsDeletedFalseOrderByStartAtAscLogIdAsc(empId, date),
                        attendanceAnomalyRepository.findByAttendanceDailyOrderByAnomalyIdAsc(daily)))
                .orElseGet(() -> {
                    // 기록이 없는 날: 그날 적용될 근무그룹과 휴일 여부만 채움
                    WorkGroup workGroup = processor.resolveWorkGroup(empId, date);
                    return DailyAttendanceResponse.empty(date, workGroup.getName(), processor.isHoliday(empId, date, workGroup));
                });
    }

    @Override
    @Transactional(readOnly = true)
    public DailyAttendanceResponse getDaily(AuthUser user, Long empId, LocalDate date) {
        requireViewable(user, empId);
        return getDaily(empId, date);
    }

    @Override
    @Transactional(readOnly = true)
    public WeeklyAttendanceResponse getWeekly(Long empId, LocalDate date) {
        LocalDate startDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate endDate = startDate.plusDays(6);

        List<AttendanceDaySummaryResponse> days = buildDaySummaries(empId, startDate, endDate);
        WorkGroup workGroup = processor.resolveWorkGroup(empId, date);

        return WeeklyAttendanceResponse.of(startDate, endDate, workGroup, days, DateTimeUtils.now().toLocalDate());
    }

    @Override
    @Transactional(readOnly = true)
    public MonthlyAttendanceResponse getMonthly(Long empId, YearMonth month) {
        LocalDate today = DateTimeUtils.now().toLocalDate();
        List<AttendanceDaySummaryResponse> days = buildDaySummaries(empId, month.atDay(1), month.atEndOfMonth());

        return MonthlyAttendanceResponse.of(month, resolveMonthlyWorkGroup(empId, month), days, today);
    }

    @Override
    @Transactional(readOnly = true)
    public AttendanceSummaryResponse getSummary(Long empId, YearMonth month) {
        List<AttendanceDaySummaryResponse> days = buildDaySummaries(empId, month.atDay(1), month.atEndOfMonth());

        return AttendanceSummaryResponse.of(empId, month, resolveMonthlyWorkGroup(empId, month), days);
    }

    @Override
    @Transactional(readOnly = true)
    public AttendanceSummaryResponse getSummary(AuthUser user, Long empId, YearMonth month) {
        // 급여 명세서 생성은 관리자 토큰으로 부르므로 그대로 허용
        requireViewable(user, empId);
        return getSummary(empId, month);
    }

    /** 관리자(인사)이거나 본인·조회 범위 안의 사원이어야 한다. 아니면 BusinessException(ACCESS_DENIED) */
    private void requireViewable(AuthUser user, Long empId) {
        if (!user.isAdmin() && !attendanceViewScope.canView(user, empId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "볼 수 없는 사원의 근태입니다.");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<AttendanceDaySummaryResponse> getDaySummaries(Long empId, LocalDate from, LocalDate to) {
        return buildDaySummaries(empId, from, to);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkStatusHistoryResponse> getHistory(Long empId, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "from은 to보다 늦을 수 없습니다.");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > HISTORY_MAX_DAYS) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "조회 기간은 최대 " + HISTORY_MAX_DAYS + "일입니다.");
        }
        return workStatusLogRepository.findByEmpIdAndWorkDateBetweenOrderByWorkDateAscStartAtAscLogIdAsc(empId, from, to)
                .stream()
                .map(WorkStatusHistoryResponse::from)
                .toList();
    }

    /** 월 단위 조회의 근무그룹. 이번 달이면 오늘, 아니면 그 달 말일 기준 */
    private WorkGroup resolveMonthlyWorkGroup(Long empId, YearMonth month) {
        LocalDate today = DateTimeUtils.now().toLocalDate();
        LocalDate baseDate = YearMonth.from(today).equals(month) ? today : month.atEndOfMonth();
        return processor.resolveWorkGroup(empId, baseDate);
    }

    /** 현재 상태에서 바꿀 수 있는 상태 목록 */
    private List<WorkStatusCode> getAbleStatuses(WorkStatusCode current) {
        // 퇴근 후에는 업무로만 바꿀 수 있음 (업무로 바꾸면 퇴근취소)
        if (WorkStatusCode.CLOCK_OUT.equals(current.getStatusCode())) {
            return List.of(processor.getStatusCode(WorkStatusCode.WORKING));
        }

        // 그 외: 출퇴근 버튼 상태, 휴가, 현재 상태 제외
        return workStatusCodeRepository.findByStatusCodeNotIn(NOT_SELECTABLE_CODES).stream()
                .filter(code -> !code.getStatusCode().equals(current.getStatusCode()))
                .toList();
    }

    /** 기간 내 날짜별 요약. 기록이 없는 날도 빈 항목으로 채운다. */
    private List<AttendanceDaySummaryResponse> buildDaySummaries(Long empId, LocalDate from, LocalDate to) {
        List<AttendanceDaily> dailies =
                attendanceDailyRepository.findByEmpIdAndWorkDateBetweenOrderByWorkDateAsc(empId, from, to);

        // 날짜 → 근태
        Map<LocalDate, AttendanceDaily> dailyByDate = dailies.stream()
                .collect(Collectors.toMap(AttendanceDaily::getWorkDate, Function.identity()));

        // 근태 ID → 근태이상 목록 (한 번에 조회 후 나눠 담기)
        Map<Long, List<AttendanceAnomaly>> anomaliesByAttId = dailies.isEmpty()
                ? Map.of()
                : attendanceAnomalyRepository.findByAttendanceDailyInOrderByAnomalyIdAsc(dailies).stream()
                .collect(Collectors.groupingBy(a -> a.getAttendanceDaily().getAttId()));

        // 날짜 → 휴가 목록
        Map<LocalDate, List<LeaveResponse>> leavesByDate = employeeLeaveRepository
                .findByEmpIdAndLeaveDateBetween(empId, from, to).stream()
                .collect(Collectors.groupingBy(EmployeeLeave::getLeaveDate,
                        Collectors.mapping(LeaveResponse::from, Collectors.toList())));

        return from.datesUntil(to.plusDays(1))
                .map(date -> {
                    List<LeaveResponse> leaves = leavesByDate.getOrDefault(date, List.of());
                    AttendanceDaily daily = dailyByDate.get(date);
                    if (daily != null) {
                        return AttendanceDaySummaryResponse.of(
                                daily, anomaliesByAttId.getOrDefault(daily.getAttId(), List.of()), leaves);
                    }
                    WorkGroup workGroup = processor.resolveWorkGroup(empId, date);
                    return AttendanceDaySummaryResponse.empty(date, processor.isHoliday(empId, date, workGroup), leaves);
                })
                .toList();
    }
}
