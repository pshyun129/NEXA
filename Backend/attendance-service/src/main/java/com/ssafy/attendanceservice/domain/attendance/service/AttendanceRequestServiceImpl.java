package com.ssafy.attendanceservice.domain.attendance.service;

import com.ssafy.attendanceservice.domain.attendance.calculator.ApprovedOvertime;
import com.ssafy.attendanceservice.domain.attendance.calculator.TimeRange;
import com.ssafy.attendanceservice.domain.attendance.calculator.WorkLogEntry;
import com.ssafy.attendanceservice.domain.attendance.calculator.WorkTimeCalculator;
import com.ssafy.attendanceservice.domain.attendance.calculator.WorkTimeResult;
import com.ssafy.attendanceservice.domain.attendance.client.ApprovalClient;
import com.ssafy.attendanceservice.domain.attendance.client.ApprovalDocument;
import com.ssafy.attendanceservice.domain.attendance.client.ApprovalDocumentCreateRequest;
import com.ssafy.attendanceservice.domain.attendance.dto.AttendanceRequestDetailResponse;
import com.ssafy.attendanceservice.domain.attendance.dto.AttendanceRequestPreviewRequest;
import com.ssafy.attendanceservice.domain.attendance.dto.AttendanceRequestPreviewResponse;
import com.ssafy.attendanceservice.domain.attendance.dto.AttendanceRequestSubmitRequest;
import com.ssafy.attendanceservice.domain.attendance.dto.AttendanceRequestSummaryResponse;
import com.ssafy.attendanceservice.domain.attendance.entity.AttendanceRequest;
import com.ssafy.attendanceservice.domain.attendance.entity.AttendanceRequestItem;
import com.ssafy.attendanceservice.domain.attendance.dto.WorkTimeResponse;
import com.ssafy.attendanceservice.domain.attendance.entity.AttendanceDaily;
import com.ssafy.attendanceservice.domain.attendance.entity.WorkGroup;
import com.ssafy.attendanceservice.domain.attendance.entity.WorkStatusCode;
import com.ssafy.attendanceservice.domain.attendance.entity.WorkStatusLog;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AttendanceRequestStatus;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AttendanceRequestType;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.RequestItemAction;
import com.ssafy.attendanceservice.domain.attendance.repository.AttendanceDailyRepository;
import com.ssafy.attendanceservice.domain.attendance.repository.AttendanceRequestRepository;
import com.ssafy.attendanceservice.domain.attendance.repository.WorkStatusCodeRepository;
import com.ssafy.attendanceservice.domain.attendance.repository.WorkStatusLogRepository;
import com.ssafy.attendanceservice.global.exception.BusinessException;
import com.ssafy.attendanceservice.global.exception.ErrorCode;
import com.ssafy.attendanceservice.global.util.DateTimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AttendanceRequestServiceImpl implements AttendanceRequestService {

    private final AttendanceRequestRepository attendanceRequestRepository;
    private final AttendanceDailyRepository attendanceDailyRepository;
    private final WorkStatusLogRepository workStatusLogRepository;
    private final WorkStatusCodeRepository workStatusCodeRepository;
    private final AttendanceDailyProcessor processor;
    private final ApprovalClient approvalClient;

    /** 시뮬레이션용 로그 한 줄 (DB에 저장하지 않음) */
    private record SimulatedLog(String statusCode, OffsetDateTime startAt, boolean countAsWork) {
    }

    @Override
    @Transactional(readOnly = true)
    public AttendanceRequestPreviewResponse preview(Long empId, AttendanceRequestPreviewRequest request) {
        // 같은 날 결재 대기 중인 같은 유형 신청이 있으면 불가
        if (attendanceRequestRepository.existsByEmpIdAndTargetDateAndReqTypeAndStatus(
                empId, request.targetDate(), request.reqType(), AttendanceRequestStatus.PENDING)) {
            throw new BusinessException(ErrorCode.DUPLICATE_PENDING_REQUEST);
        }

        // 종일 휴가 날은 근무시간 수정·초과근무 신청 불가 (휴일대체는 대체일에서 따로 확인)
        if (request.reqType() != AttendanceRequestType.HOLIDAY_SUBST
                && processor.isFullDayLeave(empId, request.targetDate())) {
            throw new BusinessException(ErrorCode.LEAVE_DAY_REQUEST);
        }

        return switch (request.reqType()) {
            case TIME_FIX -> previewTimeFix(empId, request);
            case OVERTIME -> previewOvertime(empId, request);
            case HOLIDAY_SUBST -> previewHolidaySubst(empId, request);
        };
    }

    @Override
    @Transactional(readOnly = true)
    public List<AttendanceRequestSummaryResponse> getMyRequests(Long empId, int year, AttendanceRequestType requestType) {
        // 상신 일시 기준 연도 범위 (Asia/Seoul): [year-01-01 00:00, year+1-01-01 00:00)
        OffsetDateTime from = LocalDate.of(year, 1, 1).atStartOfDay(DateTimeUtils.ZONE).toOffsetDateTime();
        OffsetDateTime to = LocalDate.of(year + 1, 1, 1).atStartOfDay(DateTimeUtils.ZONE).toOffsetDateTime();

        List<AttendanceRequest> requests = requestType == null
                ? attendanceRequestRepository
                        .findByEmpIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(empId, from, to)
                : attendanceRequestRepository
                        .findByEmpIdAndReqTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                                empId, requestType, from, to);

        return requests.stream().map(AttendanceRequestSummaryResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AttendanceRequestDetailResponse getMyRequest(Long empId, Long requestId) {
        // 없는 신청서와 다른 사람 신청서를 같은 404로 응답 (존재 여부 노출 방지)
        return attendanceRequestRepository.findById(requestId)
                .filter(request -> request.getEmpId().equals(empId))
                .map(AttendanceRequestDetailResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.ATTENDANCE_REQUEST_NOT_FOUND));
    }

    @Override
    @Transactional
    public AttendanceRequestDetailResponse cancel(Long empId, Long requestId) {
        // 1. 회수와 결재 결과가 동시에 와도 하나씩 처리. 다른 사람 신청서는 없는 것과 같은 404
        AttendanceRequest request = attendanceRequestRepository.findByIdForUpdate(requestId)
                .filter(r -> r.getEmpId().equals(empId))
                .orElseThrow(() -> new BusinessException(ErrorCode.ATTENDANCE_REQUEST_NOT_FOUND));

        // 2. 결재 대기 중인 신청서만 (승인 후 취소는 근태를 되돌려야 해서 범위 밖)
        if (!request.isPending()) {
            throw new BusinessException(ErrorCode.REQUEST_NOT_PENDING);
        }

        // 3. 결재 문서 회수 (신청자 토큰). 결재가 진행됐으면 예외로 끝나고 신청서는 그대로
        approvalClient.cancelDocument(request.getApprovalDocId());

        // 4. 바로 회수 상태로. 뒤이어 오는 CANCEL 결과 이벤트는 이미 처리된 신청이라 무시된다
        request.cancel(DateTimeUtils.now());
        return AttendanceRequestDetailResponse.from(request);
    }

    @Override
    @Transactional
    public AttendanceRequestDetailResponse submit(Long empId, AttendanceRequestSubmitRequest request) {
        AttendanceRequestType reqType = request.reqType();

        // 1. 미리보기와 같은 검증 (중복 신청, 주 최대 근로시간, 대상 로그 등)
        AttendanceRequestPreviewResponse preview = preview(empId, request.toPreviewRequest());

        // 2. 신청서 저장. 결재 문서에 신청 ID를 넘기려고 먼저 저장한다
        AttendanceRequest attendanceRequest = AttendanceRequest.builder()
                .empId(empId)
                .reqType(reqType)
                .targetDate(request.targetDate())
                .substDate(reqType == AttendanceRequestType.HOLIDAY_SUBST ? request.substDate() : null)
                .reason(request.reason())
                .build();
        if (reqType != AttendanceRequestType.HOLIDAY_SUBST) {
            request.itemsOrEmpty().forEach(item -> attendanceRequest.addItem(toRequestItem(item)));
        }
        attendanceRequestRepository.save(attendanceRequest);

        // 3. 결재 문서 생성 (신청한 사원의 토큰으로 호출). 실패하면 예외로 신청서 저장도 롤백된다
        ApprovalDocument document = approvalClient.createDocument(ApprovalDocumentCreateRequest.ofAttendance(
                titleOf(request), reqType, attendanceRequest.getAttReqId(), attributesOf(empId, request, preview)));
        attendanceRequest.linkApprovalDocument(document.documentId(), document.docNo());

        return AttendanceRequestDetailResponse.from(attendanceRequest);
    }

    /** 신청 항목 → 저장용 상세. 삭제 항목은 지울 로그의 상태·시각을 그대로 남긴다 */
    private AttendanceRequestItem toRequestItem(AttendanceRequestPreviewRequest.Item item) {
        if (item.action() == RequestItemAction.DELETE) {
            WorkStatusLog target = workStatusLogRepository.findById(item.targetLogId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT, "수정/삭제 대상 로그가 올바르지 않습니다."));
            return AttendanceRequestItem.builder()
                    .action(RequestItemAction.DELETE)
                    .targetLogId(target.getLogId())
                    .workStatusCode(target.getWorkStatusCode())
                    .startAt(target.getStartAt())
                    .build();
        }
        return AttendanceRequestItem.builder()
                .action(item.action())  // OVERTIME은 null
                .targetLogId(item.targetLogId())
                .workStatusCode(processor.getStatusCode(item.statusCode()))
                .startAt(item.startAt())
                .endAt(item.endAt())
                .build();
    }

    /** 결재 문서 제목 */
    private static String titleOf(AttendanceRequestSubmitRequest request) {
        return switch (request.reqType()) {
            case TIME_FIX -> "근무시간 수정 신청 (" + request.targetDate() + ")";
            case OVERTIME -> "초과근무 신청 (" + request.targetDate() + ")";
            case HOLIDAY_SUBST -> "휴일대체 신청 (" + request.targetDate() + " → " + request.substDate() + ")";
        };
    }

    /** 결재 전결 판단 값. 초과근무만 신청 시간과 그 달 누적(이번 신청 포함)을 보낸다 */
    private ApprovalDocumentCreateRequest.Attributes attributesOf(Long empId, AttendanceRequestSubmitRequest request,
                                                                 AttendanceRequestPreviewResponse preview) {
        if (request.reqType() != AttendanceRequestType.OVERTIME) {
            return null;
        }
        int requestedMin = preview.overtime().requestedMin();
        return new ApprovalDocumentCreateRequest.Attributes(
                requestedMin, monthlyOvertimeMinExcept(empId, request.targetDate()) + requestedMin);
    }

    /** 대상일이 속한 달에서 대상일을 제외한 초과근로(연장 + 휴일) 합 */
    private int monthlyOvertimeMinExcept(Long empId, LocalDate targetDate) {
        YearMonth month = YearMonth.from(targetDate);
        return attendanceDailyRepository
                .findByEmpIdAndWorkDateBetweenOrderByWorkDateAsc(empId, month.atDay(1), month.atEndOfMonth()).stream()
                .filter(d -> !d.getWorkDate().equals(targetDate))
                .mapToInt(d -> d.getOtExtendedMin() + d.getOtHolidayMin())
                .sum();
    }

    @Override
    @Transactional
    public void applyApprovalResult(Long attReqId, AttendanceRequestStatus result) {
        if (result == AttendanceRequestStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "결재 결과는 승인·반려·회수 중 하나여야 합니다.");
        }

        // 1. 같은 신청의 결과가 동시에 와도 하나씩 처리
        AttendanceRequest request = attendanceRequestRepository.findByIdForUpdate(attReqId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ATTENDANCE_REQUEST_NOT_FOUND));

        // 2. 이미 반영된 신청이면 무시 (같은 결과 중복 수신, 반려 후 승인 등)
        if (!request.isPending()) {
            log.info("결재 결과 무시 (이미 처리됨): attReqId={}, 현재={}, 받은 결과={}",
                    attReqId, request.getStatus(), result);
            return;
        }

        // 3. 상태 변경. 승인이면 신청 내용을 근태에 반영 (재계산이 승인 상태를 조회하므로 상태를 먼저 바꾼다)
        OffsetDateTime now = DateTimeUtils.now();
        switch (result) {
            case APPROVED -> {
                request.approve(now);
                applyApproved(request);
            }
            case REJECTED -> request.reject(now);
            case CANCELED -> request.cancel(now);
            case PENDING -> throw new IllegalStateException("위에서 걸러짐");
        }
    }

    /** 승인된 신청을 근태에 반영 */
    private void applyApproved(AttendanceRequest request) {
        Long empId = request.getEmpId();
        switch (request.getReqType()) {
            case TIME_FIX -> processor.applyTimeFix(request);
            case OVERTIME -> attendanceDailyRepository.findByEmpIdAndWorkDate(empId, request.getTargetDate())
                    .ifPresent(processor::syncDaily);
            case HOLIDAY_SUBST -> {
                processor.resyncHoliday(empId, request.getTargetDate());
                processor.resyncHoliday(empId, request.getSubstDate());
            }
        }
    }

    // ===== 휴일대체 =====

    // 휴일대체 중복으로 보는 상태 (결재 대기, 승인)
    private static final List<AttendanceRequestStatus> ACTIVE_STATUSES =
            List.of(AttendanceRequestStatus.PENDING, AttendanceRequestStatus.APPROVED);

    private AttendanceRequestPreviewResponse previewHolidaySubst(Long empId, AttendanceRequestPreviewRequest request) {
        LocalDate targetDate = request.targetDate();
        LocalDate substDate = request.substDate();
        if (substDate == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "대체일은 필수입니다.");
        }

        // 1. 대상일은 휴일 (공휴일 또는 근무 요일 아님)
        if (!processor.isHoliday(empId, targetDate, processor.resolveWorkGroup(empId, targetDate))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "대상일은 휴일이어야 합니다.");
        }

        // 2. 대체일은 근무일
        if (processor.isHoliday(empId, substDate, processor.resolveWorkGroup(empId, substDate))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "대체일은 근무일이어야 합니다.");
        }

        // 3. 대체일에 이미 휴가(반차 포함)가 있으면 불가
        if (!processor.leavePortions(empId, substDate).isEmpty()) {
            throw new BusinessException(ErrorCode.LEAVE_DAY_REQUEST, "대체일에 이미 휴가가 있습니다.");
        }

        // 4. 같은 휴일이나 같은 대체일로 진행 중/승인된 휴일대체가 있으면 불가
        if (attendanceRequestRepository.existsByEmpIdAndReqTypeAndTargetDateAndStatusIn(
                empId, AttendanceRequestType.HOLIDAY_SUBST, targetDate, ACTIVE_STATUSES)
                || attendanceRequestRepository.existsByEmpIdAndReqTypeAndSubstDateAndStatusIn(
                empId, AttendanceRequestType.HOLIDAY_SUBST, substDate, ACTIVE_STATUSES)) {
            throw new BusinessException(ErrorCode.DUPLICATE_PENDING_REQUEST);
        }

        return AttendanceRequestPreviewResponse.ofHolidaySubst(targetDate,
                new AttendanceRequestPreviewResponse.HolidaySubst(targetDate, substDate));
    }

    // ===== 초과근무 =====

    private AttendanceRequestPreviewResponse previewOvertime(Long empId, AttendanceRequestPreviewRequest request) {
        LocalDate targetDate = request.targetDate();
        if (request.itemsOrEmpty().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "초과근무 시간이 없습니다.");
        }

        // 1. 신청 시간 합계 (항목별 시작 < 종료 ≤ 근무일 마감, 근로 상태)
        int requestedMin = 0;
        for (AttendanceRequestPreviewRequest.Item item : request.itemsOrEmpty()) {
            validateOvertimeItem(item, targetDate);
            requestedMin += (int) Duration.between(item.startAt(), item.endAt()).toMinutes();
        }

        // 1-1. 구간끼리, 그리고 그날 이미 승인된 초과근무와 겹치면 불가 (같은 시간을 두 번 신청하지 않게)
        validateNoOverlap(empId, targetDate, request.itemsOrEmpty());

        // 2. 그날 근태와 근무그룹
        Optional<AttendanceDaily> daily = attendanceDailyRepository.findByEmpIdAndWorkDate(empId, targetDate);
        WorkGroup workGroup = daily.map(AttendanceDaily::getWorkGroup)
                .orElseGet(() -> processor.resolveWorkGroup(empId, targetDate));

        // 3. 주간 근로시간: 이번 주 실제 근로 + 신청 시간 + 다른 날 결재 대기 초과근무.
        //    신청 구간 중 이미 일해서 근로시간에 들어 있는 부분(미승인 초과근로)은 두 번 세지 않는다
        List<TimeRange> requested = request.itemsOrEmpty().stream()
                .map(item -> new TimeRange(item.startAt(), item.endAt()))
                .toList();
        int targetDayMin = daily.map(AttendanceDaily::getTotalMin).orElse(0)
                + notYetCountedMin(requested, daily);
        int weeklyTotalMin = weeklyTotalMinExcept(empId, targetDate) + targetDayMin
                + pendingOvertimeMinExcept(empId, targetDate);
        int weeklyMaxMin = workGroup.getWeeklyMaxHours() * 60;

        // 4. 주 최대 근로시간 초과 불가
        if (weeklyTotalMin > weeklyMaxMin) {
            throw new BusinessException(ErrorCode.WEEKLY_MAX_HOURS_EXCEEDED);
        }

        return AttendanceRequestPreviewResponse.ofOvertime(targetDate, new AttendanceRequestPreviewResponse.Overtime(
                requestedMin, weeklyTotalMin, weeklyMaxMin, weeklyMaxMin - weeklyTotalMin));
    }

    private void validateOvertimeItem(AttendanceRequestPreviewRequest.Item item, LocalDate workDate) {
        if (item.endAt() == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "종료 시각은 필수입니다.");
        }
        processor.validateStartAt(workDate, item.startAt());
        if (!item.startAt().isBefore(item.endAt())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "초과근무 종료 시각은 시작 시각보다 늦어야 합니다.");
        }
        // 근무일은 다음 날 06:00에 마감된다 (퇴근도 그 전까지만 찍힌다)
        OffsetDateTime cutoff = workDate.plusDays(1).atTime(AttendanceDaily.WORK_DAY_CUTOFF)
                .atZone(DateTimeUtils.ZONE).toOffsetDateTime();
        if (item.endAt().isAfter(cutoff)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "초과근무는 다음 날 06:00(근무일 마감)까지만 신청할 수 있습니다.");
        }
        WorkStatusCode statusCode = workStatusCodeRepository.findById(String.valueOf(item.statusCode()))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT, "존재하지 않는 근무 상태입니다."));
        if (!statusCode.getCountAsWork()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "초과근무는 근로시간에 포함되는 상태로만 신청할 수 있습니다.");
        }
    }

    private void validateNoOverlap(Long empId, LocalDate workDate, List<AttendanceRequestPreviewRequest.Item> items) {
        List<AttendanceRequestPreviewRequest.Item> sorted = items.stream()
                .sorted(Comparator.comparing(AttendanceRequestPreviewRequest.Item::startAt))
                .toList();
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).startAt().isBefore(sorted.get(i - 1).endAt())) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "초과근무 구간끼리 겹치면 안 됩니다.");
            }
        }
        for (ApprovedOvertime approved : processor.approvedOvertimes(empId, workDate)) {
            for (AttendanceRequestPreviewRequest.Item item : sorted) {
                if (item.startAt().isBefore(approved.endAt()) && approved.startAt().isBefore(item.endAt())) {
                    throw new BusinessException(ErrorCode.INVALID_INPUT, String.format(
                            "이미 승인된 초과근무(%s~%s)와 겹칩니다. 겹치지 않는 시간만 신청해 주세요.",
                            hhmm(approved.startAt()), hhmm(approved.endAt())));
                }
            }
        }
    }

    /** 신청 구간 중 아직 근로시간에 들어가지 않은 분 (이미 일한 미승인 초과근로와 겹치는 부분은 뺀다) */
    private int notYetCountedMin(List<TimeRange> requested, Optional<AttendanceDaily> daily) {
        List<TimeRange> worked = daily.map(processor::unapprovedOtRanges).orElse(List.of());
        long total = 0;
        for (TimeRange r : requested) {
            long overlap = 0;
            for (TimeRange w : worked) {
                OffsetDateTime start = r.startAt().isAfter(w.startAt()) ? r.startAt() : w.startAt();
                OffsetDateTime end = r.endAt().isBefore(w.endAt()) ? r.endAt() : w.endAt();
                if (start.isBefore(end)) {
                    overlap += Duration.between(start, end).toMinutes();
                }
            }
            total += Duration.between(r.startAt(), r.endAt()).toMinutes() - overlap;
        }
        return (int) total;
    }

    /** 같은 주 다른 날의 결재 대기 초과근무 중 아직 근로시간에 들어가지 않은 분 (여러 건을 동시에 올려 주 한도를 넘지 않게) */
    private int pendingOvertimeMinExcept(Long empId, LocalDate targetDate) {
        LocalDate monday = targetDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return attendanceRequestRepository.findByEmpIdAndReqTypeAndStatusAndTargetDateBetween(
                        empId, AttendanceRequestType.OVERTIME, AttendanceRequestStatus.PENDING, monday, monday.plusDays(6))
                .stream()
                .filter(pending -> !pending.getTargetDate().equals(targetDate))
                .mapToInt(pending -> notYetCountedMin(
                        pending.getItems().stream()
                                .filter(item -> item.getStartAt() != null && item.getEndAt() != null)
                                .map(item -> new TimeRange(item.getStartAt(), item.getEndAt()))
                                .toList(),
                        attendanceDailyRepository.findByEmpIdAndWorkDate(empId, pending.getTargetDate())))
                .sum();
    }

    private static String hhmm(OffsetDateTime at) {
        return at.atZoneSameInstant(DateTimeUtils.ZONE).toLocalTime().toString().substring(0, 5);
    }

    // ===== 근무시간 수정 =====

    private AttendanceRequestPreviewResponse previewTimeFix(Long empId, AttendanceRequestPreviewRequest request) {
        LocalDate targetDate = request.targetDate();

        // 1. 대상일은 오늘 이전이나 오늘
        if (targetDate.isAfter(DateTimeUtils.now().toLocalDate())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "미래 날짜의 근무시간은 수정할 수 없습니다.");
        }
        if (request.itemsOrEmpty().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "수정할 내용(items)이 없습니다.");
        }

        // 2. 현재 로그를 시뮬레이션 목록으로 (logId → 로그)
        Map<Long, SimulatedLog> current = new LinkedHashMap<>();
        workStatusLogRepository.findByEmpIdAndWorkDateAndIsDeletedFalseOrderByStartAtAscLogIdAsc(empId, targetDate)
                .forEach(log -> current.put(log.getLogId(), toSimulated(log)));
        List<SimulatedLog> added = new ArrayList<>();

        // 3. 신청 내용을 시뮬레이션에 적용 (DB는 그대로)
        for (AttendanceRequestPreviewRequest.Item item : request.itemsOrEmpty()) {
            if (item.action() == null) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "action은 필수입니다.");
            }
            switch (item.action()) {
                case ADD -> added.add(toSimulated(item, targetDate));
                case MODIFY -> {
                    validateTargetLog(current, item.targetLogId());
                    current.put(item.targetLogId(), toSimulated(item, targetDate));
                }
                case DELETE -> {
                    validateTargetLog(current, item.targetLogId());
                    current.remove(item.targetLogId());
                }
            }
        }

        List<SimulatedLog> after = new ArrayList<>(current.values());
        after.addAll(added);

        // 4. 적용 결과에서 출근/퇴근은 각각 1건 이하
        if (countByCode(after, WorkStatusCode.CLOCK_IN) > 1 || countByCode(after, WorkStatusCode.CLOCK_OUT) > 1) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "출근과 퇴근은 하루에 각각 1건만 등록할 수 있습니다.");
        }

        // 5. 수정 후 근로시간 계산 (그날 근무그룹, 휴일 여부 기준)
        Optional<AttendanceDaily> daily = attendanceDailyRepository.findByEmpIdAndWorkDate(empId, targetDate);
        WorkGroup workGroup = daily.map(AttendanceDaily::getWorkGroup)
                .orElseGet(() -> processor.resolveWorkGroup(empId, targetDate));
        boolean isHoliday = daily.map(AttendanceDaily::getIsHoliday)
                .orElseGet(() -> processor.isHoliday(empId, targetDate, workGroup));

        OffsetDateTime clockOut = after.stream()
                .filter(log -> WorkStatusCode.CLOCK_OUT.equals(log.statusCode()))
                .map(SimulatedLog::startAt)
                .reduce((first, second) -> second)
                .orElse(null);
        WorkTimeResult afterWorkTime = WorkTimeCalculator.calculate(
                after.stream().map(log -> new WorkLogEntry(log.startAt(), log.countAsWork())).toList(),
                clockOut,
                workGroup.getDailyStdMinutes(),
                workGroup.getBreakMinutes(),
                isHoliday,
                processor.approvedOvertimes(empId, targetDate));

        // 6. 주간 총 근로시간 (그날은 수정 후 값으로 대체)
        int weeklyTotalMin = weeklyTotalMinExcept(empId, targetDate) + afterWorkTime.totalMin();

        return AttendanceRequestPreviewResponse.ofTimeFix(targetDate, new AttendanceRequestPreviewResponse.TimeFix(
                daily.map(WorkTimeResponse::from).orElseGet(WorkTimeResponse::empty),
                WorkTimeResponse.from(afterWorkTime),
                weeklyTotalMin,
                workGroup.getWeeklyMaxHours() * 60));
    }

    /** 수정/삭제 대상은 본인의 그날 로그(삭제 제외)이고 퇴근취소가 아니어야 함 */
    private void validateTargetLog(Map<Long, SimulatedLog> current, Long targetLogId) {
        if (targetLogId == null || !current.containsKey(targetLogId)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "수정/삭제 대상 로그가 올바르지 않습니다.");
        }
        if (WorkStatusCode.CLOCK_OUT_CANCEL.equals(current.get(targetLogId).statusCode())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "퇴근취소 기록은 수정/삭제할 수 없습니다.");
        }
    }

    private SimulatedLog toSimulated(WorkStatusLog log) {
        return new SimulatedLog(
                log.getWorkStatusCode().getStatusCode(), log.getStartAt(), log.getWorkStatusCode().getCountAsWork());
    }

    /** 신청 항목 검증 후 시뮬레이션 로그로 변환 (추가/수정용) */
    private SimulatedLog toSimulated(AttendanceRequestPreviewRequest.Item item, LocalDate workDate) {
        if (item.statusCode() == null || WorkStatusCode.CLOCK_OUT_CANCEL.equals(item.statusCode())
                || WorkStatusCode.LEAVE.equals(item.statusCode())) {  // 휴가는 휴가 결재로만
            throw new BusinessException(ErrorCode.INVALID_INPUT, "등록할 수 없는 근무 상태입니다.");
        }
        WorkStatusCode statusCode = workStatusCodeRepository.findById(item.statusCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT, "존재하지 않는 근무 상태입니다."));
        processor.validateStartAt(workDate, item.startAt());
        return new SimulatedLog(statusCode.getStatusCode(), item.startAt(), statusCode.getCountAsWork());
    }

    private long countByCode(List<SimulatedLog> logs, String statusCode) {
        return logs.stream().filter(log -> statusCode.equals(log.statusCode())).count();
    }

    /** 대상일이 포함된 주(월~일)에서 대상일을 제외한 근로시간 합 */
    private int weeklyTotalMinExcept(Long empId, LocalDate targetDate) {
        LocalDate monday = targetDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return attendanceDailyRepository
                .findByEmpIdAndWorkDateBetweenOrderByWorkDateAsc(empId, monday, monday.plusDays(6)).stream()
                .filter(d -> !d.getWorkDate().equals(targetDate))
                .mapToInt(AttendanceDaily::getTotalMin)
                .sum();
    }
}