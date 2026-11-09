package com.ssafy.leaveservice.domain.request.service;

import com.ssafy.leaveservice.client.attendance.HolidayClient.HolidayResult;
import com.ssafy.leaveservice.domain.account.entity.LeaveAccount;
import com.ssafy.leaveservice.domain.account.repository.LeaveAccountRepository;
import com.ssafy.leaveservice.domain.leavetype.repository.LeaveTypeRepository;
import com.ssafy.leaveservice.domain.ledger.entity.LeaveLedger;
import com.ssafy.leaveservice.domain.ledger.repository.LeaveLedgerRepository;
import com.ssafy.leaveservice.domain.request.dto.*;
import com.ssafy.leaveservice.domain.request.dto.LeaveRequestPreviewResponse.*;
import com.ssafy.leaveservice.domain.request.entity.*;
import com.ssafy.leaveservice.domain.request.repository.*;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.*;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.OffsetDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class LeaveSubmissionTransactionService {
    private final EmployeeAccess employeeAccess;
    private final Validator validator;
    private final SubmissionPayloadHash payloadHash;
    private final LeaveSubmissionLockRepository locks;
    private final LeaveRequestValidationService validationService;
    private final LeaveRequestRepository requests;
    private final LeaveRequestDateRepository dates;
    private final LeaveRequestAllocationRepository allocations;
    private final LeaveLedgerRepository ledger;
    private final LeaveTypeRepository types;
    private final LeaveAccountRepository accounts;

    @Transactional(readOnly = true)
    public Optional<LeaveSubmissionResult> findExisting(String key, String hash) {
        Long employeeId = currentEmployee();
        if (key == null || !key.matches("[!-~]{1,128}") || hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return findExisting(employeeId, key, hash);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public LeaveSubmissionResult submit(
            String key,
            String hash,
            LeaveRequestPreviewRequest input,
            HolidayResult holidays
    ) {
        // 직접 호출에도 본인 인증·입력·해시 검증 적용
        Long employeeId = currentEmployee();
        if (input == null || !validator.validate(input).isEmpty() || key == null || !key.matches("[!-~]{1,128}")
                || !payloadHash.calculate(input).equals(hash)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        // 직원별 직렬화 후 멱등성 키를 반드시 다시 조회
        locks.lockEmployee(employeeId);
        Optional<LeaveSubmissionResult> existing = findExisting(employeeId, key, hash);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (holidays == null || !holidays.verified()) {
            throw new BusinessException(ErrorCode.HOLIDAY_VERIFICATION_UNAVAILABLE);
        }

        // 정책·유형·발생분 잠금 이후 최신 DB 기준 시각으로 공통 검증과 FEFO 재수행
        LeaveSubmissionLockRepository.LockedPolicy lockedPolicy = locks.lockSettings(employeeId, input.leaveTypeId());
        OffsetDateTime asOf = locks.currentTime();
        LeaveRequestPreviewResponse checked = validationService.validate(input, asOf, holidays);
        if (!Objects.equals(lockedPolicy.versionId(), checked.policyVersionId()) || !checked.requestable()) {
            throw new BusinessException(ErrorCode.LEAVE_REQUEST_NOT_ALLOWED,
                    checked.reasons().isEmpty() ? "잠금 중 정책 배정이 변경되었습니다. 다시 시도해주세요."
                            : checked.reasons().getFirst().message());
        }

        // 신청과 결재 호출 전 상태를 먼저 저장하며 문서 ID는 생성하지 않음
        LeaveRequest request = LeaveRequest.submitting(employeeId, types.getReferenceById(input.leaveTypeId()),
                checked.policyVersionId(), checked.totalRequestedMinutes(), checked.balanceDeductionMinutes(),
                input.reason(), key, hash, asOf);
        requests.saveAndFlush(request);

        LeaveAccount account = null;
        if (checked.balanceRequired()) {
            Long balanceTypeId = request.getLeaveType().getBalanceType().getBalanceTypeId();
            account = accounts.findByEmployeeIdAndBalanceTypeBalanceTypeId(employeeId, balanceTypeId).orElseThrow(
                    () -> new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION));
        }

        // 날짜별 신청 시간과 실제 배분을 저장하고 배분마다 RESERVE 기록
        for (DateResponse checkedDate : checked.dates()) {
            LeaveRequestDate date = LeaveRequestDate.requested(request,
                    checkedDate.leaveDate().atStartOfDay(PreviewGrantAllocator.SEOUL).toOffsetDateTime(),
                    checkedDate.portion(), checkedDate.requestedMinutes(), checkedDate.balanceDeductionMinutes());
            dates.saveAndFlush(date);
            int allocatedMinutes = 0;
            for (Allocation part : checkedDate.allocations()) {
                int minutes = Math.toIntExact(part.minutes());
                allocatedMinutes = Math.addExact(allocatedMinutes, minutes);
                LeaveRequestAllocation allocation = LeaveRequestAllocation.reserve(request.getRequestId(), date.getRequestDateId(),
                        employeeId, account.getAccountId(), part.grantId(), minutes, asOf);
                allocations.saveAndFlush(allocation);
                ledger.saveAndFlush(LeaveLedger.reserve(account, allocation, asOf));
            }
            if (allocatedMinutes != date.getDeductMinutes()) {
                throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
            }
        }
        return LeaveSubmissionResult.from(request);
    }

    private Optional<LeaveSubmissionResult> findExisting(Long employeeId, String key, String hash) {
        Optional<LeaveRequest> existing = requests.findByEmployeeIdAndSubmissionKey(employeeId, key);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        LeaveRequest request = existing.get();
        if (!Objects.equals(request.getSubmissionPayloadHash(), hash)) {
            throw new BusinessException(ErrorCode.LEAVE_SUBMISSION_KEY_CONFLICT);
        }
        return Optional.of(LeaveSubmissionResult.from(request));
    }

    private Long currentEmployee() {
        Long employeeId = employeeAccess.currentEmployeeId();
        if (employeeId == null || employeeId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return employeeId;
    }
}
