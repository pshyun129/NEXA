package com.ssafy.leaveservice.domain.request.service;

import com.ssafy.leaveservice.client.attendance.HolidayClient.*;
import com.ssafy.leaveservice.domain.account.dto.MyLeaveBalanceResponse;
import com.ssafy.leaveservice.domain.account.repository.LeaveAccountRepository;
import com.ssafy.leaveservice.domain.account.service.MyLeaveBalanceService;
import com.ssafy.leaveservice.domain.leavetype.dto.RequestableLeaveTypeResponse;
import com.ssafy.leaveservice.domain.leavetype.entity.*;
import com.ssafy.leaveservice.domain.leavetype.repository.LeaveTypeRepository;
import com.ssafy.leaveservice.domain.leavetype.service.RequestableLeaveTypeService;
import com.ssafy.leaveservice.domain.policy.dto.MyLeavePolicyResponse;
import com.ssafy.leaveservice.domain.policy.entity.PolicyAllowedUnits;
import com.ssafy.leaveservice.domain.policy.service.MyLeavePolicyService;
import com.ssafy.leaveservice.domain.request.dto.*;
import com.ssafy.leaveservice.domain.request.dto.LeaveRequestPreviewResponse.*;
import com.ssafy.leaveservice.domain.request.repository.LeaveRequestRepository;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.*;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class LeaveRequestValidationService {
    private final EmployeeAccess employeeAccess;
    private final MyLeavePolicyService policyService;
    private final RequestableLeaveTypeService typeService;
    private final LeaveTypeRepository typeRepository;
    private final LeaveRequestRepository requestRepository;
    private final MyLeaveBalanceService balanceService;
    private final LeaveAccountRepository accountRepository;
    private final PreviewGrantAllocator allocator;
    private final Validator validator;

    @Transactional(propagation = Propagation.MANDATORY)
    public LeaveRequestPreviewResponse validate(
            LeaveRequestPreviewRequest request,
            OffsetDateTime asOf,
            HolidayResult holidays
    ) {
        // 직접 호출하더라도 본인 인증과 입력 검증 유지
        Long employeeId = employeeAccess.currentEmployeeId();
        if (employeeId == null || employeeId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        // 현재 배정과 활성 버전, 기존 신청 가능 유형 검증 재사용
        MyLeavePolicyResponse policy = policyService.getMyPolicyAt(asOf);
        List<RequestableLeaveTypeResponse> types = typeService.getRequestableTypesAt(asOf);
        LeaveType leaveType = typeRepository.findById(request.leaveTypeId()).orElse(null);
        RequestableLeaveTypeResponse settings = types.stream()
                .filter(type -> type.leaveTypeId().equals(request.leaveTypeId())).findFirst().orElse(null);
        List<Reason> reasons = new ArrayList<>();
        if (leaveType == null || settings == null) {
            reasons.add(new Reason("TYPE_NOT_REQUESTABLE", null, "현재 정책에서 신청할 수 없는 휴가 유형입니다."));
        }

        // 날짜별 고정 시간, 과거·중복 날짜 및 근무일 검증
        List<LeaveRequestPreviewRequest.DateRequest> dates = request.dates().stream()
                .sorted(Comparator.comparing(LeaveRequestPreviewRequest.DateRequest::leaveDate)).toList();
        Set<LocalDate> uniqueDates = new HashSet<>();
        int totalMinutes = 0;
        List<DateResponse> dateResponses = new ArrayList<>();
        boolean balanceRequired = leaveType != null && leaveType.getBalanceMode() == BalanceMode.ACCOUNT;
        for (LeaveRequestPreviewRequest.DateRequest date : dates) {
            int minutes = PreviewGrantAllocator.requestedMinutes(date.portion());
            totalMinutes = Math.addExact(totalMinutes, minutes);
            dateResponses.add(new DateResponse(date.leaveDate(), date.portion(), minutes, balanceRequired ? minutes : 0, List.of()));
            if (!uniqueDates.add(date.leaveDate())) {
                reasons.add(new Reason("DUPLICATE_DATE", date.leaveDate(), "같은 날짜에는 하나의 신청 단위만 사용할 수 있습니다."));
            }
            if (date.leaveDate().isBefore(asOf.atZoneSameInstant(PreviewGrantAllocator.SEOUL).toLocalDate())) {
                reasons.add(new Reason("PAST_DATE", date.leaveDate(), "과거 날짜에는 휴가를 신청할 수 없습니다."));
            }
            reasons.addAll(LeaveRequestCalendarValidation.validateDate(date.leaveDate(), holidays));
            if (settings != null) {
                PolicyAllowedUnits allowed = settings.allowedUnits();
                boolean full = date.portion() == com.ssafy.leaveservice.domain.request.entity.LeaveRequestPortion.FULL;
                if (allowed != PolicyAllowedUnits.MIXED && allowed != (full ? PolicyAllowedUnits.FULL : PolicyAllowedUnits.HALF)) {
                    reasons.add(new Reason("UNIT_NOT_ALLOWED", date.leaveDate(), "정책에서 허용하지 않는 신청 단위입니다."));
                }
                if (minutes % policy.unitMinutes() != 0) {
                    reasons.add(new Reason("UNIT_MINUTES_MISMATCH", date.leaveDate(), "신청 시간이 정책 시간 단위의 배수가 아닙니다."));
                }
            }
        }
        if (!holidays.verified()) {
            reasons.add(new Reason("HOLIDAY_SERVICE_UNAVAILABLE", null, "근태 서비스의 공휴일 검증을 완료하지 못했습니다."));
        }
        if (settings != null && ((settings.effectiveMinMinutes() != null && totalMinutes < settings.effectiveMinMinutes())
                || (settings.effectiveMaxMinutes() != null && totalMinutes > settings.effectiveMaxMinutes()))) {
            reasons.add(new Reason("REQUEST_MINUTES_LIMIT", null, "전체 신청 시간이 정책 또는 휴가 유형의 제한을 벗어납니다."));
        }

        // 직원 소유권과 제출·승인 상태로 기존 신청 날짜 조회
        LocalDate start = dates.getFirst().leaveDate();
        LocalDate end = dates.getLast().leaveDate().plusDays(1);
        List<OffsetDateTime> blockedDates = requestRepository.findBlockingDates(employeeId,
                start.atStartOfDay(PreviewGrantAllocator.SEOUL).toOffsetDateTime(),
                end.atStartOfDay(PreviewGrantAllocator.SEOUL).toOffsetDateTime());
        Set<LocalDate> blocked = new HashSet<>();
        for (OffsetDateTime blockedDate : blockedDates) {
            blocked.add(blockedDate.atZoneSameInstant(PreviewGrantAllocator.SEOUL).toLocalDate());
        }
        for (LocalDate date : uniqueDates) {
            if (blocked.contains(date)) {
                reasons.add(new Reason("EXISTING_REQUEST", date, "동일 날짜에 제출되거나 승인된 휴가 신청이 있습니다."));
            }
        }

        // 현재 잔액 계산은 기존 API와 공유하고 미래 날짜 배분은 발생분별 FEFO로 수행
        Long remainingMinutes = null;
        if (balanceRequired && settings != null) {
            Long balanceTypeId = leaveType.getBalanceType().getBalanceTypeId();
            List<MyLeaveBalanceResponse> balances = balanceService.getMyBalancesAt(asOf);
            remainingMinutes = 0L;
            for (MyLeaveBalanceResponse balance : balances) {
                if (balance.balanceTypeId().equals(balanceTypeId)) {
                    remainingMinutes = balance.remainingMinutes();
                }
            }
            if (uniqueDates.size() == dates.size()) {
                PreviewGrantAllocator.AllocationResult allocation = allocator.allocate(dates,
                        accountRepository.findPreviewGrantBalances(employeeId, balanceTypeId, asOf));
                dateResponses = allocation.dates();
                reasons.addAll(allocation.reasons());
            }
        }

        // 업무 불가와 필수 검증 불가를 구분해 결과 반환
        boolean unverifiable = reasons.stream().anyMatch(reason -> reason.code().equals("HOLIDAY_SERVICE_UNAVAILABLE")
                || reason.code().equals("GRANT_VALIDITY_BOUNDARY"));
        ValidationStatus status = unverifiable ? ValidationStatus.UNVERIFIABLE
                : reasons.isEmpty() ? ValidationStatus.REQUESTABLE : ValidationStatus.NOT_REQUESTABLE;
        return new LeaveRequestPreviewResponse(asOf, request.leaveTypeId(), policy.policyVersionId(),
                status == ValidationStatus.REQUESTABLE, status,
                holidays.verified() ? HolidayVerification.VERIFIED : HolidayVerification.UNAVAILABLE,
                totalMinutes, balanceRequired ? totalMinutes : 0, balanceRequired, remainingMinutes,
                List.copyOf(dateResponses), List.copyOf(reasons));
    }
}
