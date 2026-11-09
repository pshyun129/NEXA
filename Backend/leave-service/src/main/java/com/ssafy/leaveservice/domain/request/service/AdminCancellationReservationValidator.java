package com.ssafy.leaveservice.domain.request.service;

import com.ssafy.leaveservice.domain.account.repository.LeaveAccountRepository;
import com.ssafy.leaveservice.domain.account.repository.projection.PreviewGrantBalance;
import com.ssafy.leaveservice.domain.account.service.LeaveBalanceAmounts;
import com.ssafy.leaveservice.domain.ledger.entity.LeaveLedger;
import com.ssafy.leaveservice.domain.ledger.entity.LedgerEventType;
import com.ssafy.leaveservice.domain.ledger.repository.LeaveLedgerRepository;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequest;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestAllocation;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestDate;
import com.ssafy.leaveservice.domain.request.repository.LeaveRequestAllocationRepository;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class AdminCancellationReservationValidator {
    private final LeaveRequestAllocationRepository allocationRepository;
    private final LeaveLedgerRepository ledgerRepository;
    private final LeaveAccountRepository accountRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public List<LeaveRequestAllocation> findReleasableAllocations(LeaveRequest request, OffsetDateTime asOf) {
        // 최초 배분과 요청 원장 조회
        List<LeaveRequestAllocation> allocations = allocationRepository.findAllByRequestIdOrderByAllocationIdAsc(request.getRequestId());
        List<LeaveLedger> entries = ledgerRepository.findAllByLeaveReqIdOrderByEntryIdAsc(request.getRequestId());
        Map<Long, LeaveRequestAllocation> unmatchedAllocations = new HashMap<>();
        Map<Long, Long> allocatedByDate = new HashMap<>();
        Map<Long, Long> releaseByGrant = new HashMap<>();
        long totalAllocated = 0;
        try {
            for (LeaveRequestAllocation allocation : allocations) {
                if (!Objects.equals(allocation.getRequestId(), request.getRequestId())
                        || !Objects.equals(allocation.getEmployeeId(), request.getEmployeeId())
                        || allocation.getAllocatedMinutes() <= 0
                        || unmatchedAllocations.put(allocation.getAllocationId(), allocation) != null) {
                    throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
                }
                totalAllocated = Math.addExact(totalAllocated, allocation.getAllocatedMinutes());
                allocatedByDate.merge(allocation.getRequestDateId(), (long) allocation.getAllocatedMinutes(), Math::addExact);
                releaseByGrant.merge(allocation.getGrantId(), (long) allocation.getAllocatedMinutes(), Math::addExact);
            }

            // 날짜별 차감량과 신청 합계는 저장 당시 값으로 검증
            long totalDeductMinutes = 0;
            for (LeaveRequestDate date : request.getDates()) {
                long allocated = allocatedByDate.getOrDefault(date.getRequestDateId(), 0L);
                allocatedByDate.remove(date.getRequestDateId());
                if (allocated != date.getDeductMinutes()) {
                    throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
                }
                totalDeductMinutes = Math.addExact(totalDeductMinutes, date.getDeductMinutes());
            }
            if (!allocatedByDate.isEmpty() || totalAllocated != request.getTotalDeductMinutes()
                    || totalDeductMinutes != totalAllocated) {
                throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
            }
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
        }

        // 배분당 원래 RESERVE 하나만 허용하며 해제·사용·역분개된 예약은 거부
        List<Long> reserveEntryIds = new ArrayList<>();
        for (LeaveLedger entry : entries) {
            LeaveRequestAllocation allocation = unmatchedAllocations.remove(entry.getAllocationId());
            if (allocation == null || entry.getEventType() != LedgerEventType.RESERVE
                    || entry.getQuantityDelta() != 0 || entry.getReservedDelta() != allocation.getAllocatedMinutes()
                    || !Objects.equals(entry.getAccount().getAccountId(), allocation.getAccountId())
                    || !Objects.equals(entry.getGrantId(), allocation.getGrantId())
                    || entry.getReversesEntryId() != null || entry.getCreatedAt().isAfter(asOf)) {
                throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
            }
            reserveEntryIds.add(entry.getEntryId());
        }
        if (!unmatchedAllocations.isEmpty()
                || (!reserveEntryIds.isEmpty() && ledgerRepository.existsByReversesEntryIdIn(reserveEntryIds))) {
            throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
        }

        // 만료·미래 발생분도 원래 예약 소속이면 공통 원장 합계로 검증
        if (!releaseByGrant.isEmpty()) {
            List<PreviewGrantBalance> balances = accountRepository.findRecordedGrantBalances(request.getEmployeeId(), asOf);
            for (PreviewGrantBalance balance : balances) {
                Long releaseMinutes = releaseByGrant.remove(balance.getGrantId());
                if (releaseMinutes == null) {
                    continue;
                }
                LeaveBalanceAmounts amounts = LeaveBalanceAmounts.from(balance.getQuantityMinutes(), balance.getReservedMinutes());
                BigDecimal remainingReserved = BigDecimal.valueOf(amounts.reservedMinutes()).subtract(BigDecimal.valueOf(releaseMinutes));
                LeaveBalanceAmounts.from(BigDecimal.valueOf(amounts.quantityMinutes()), remainingReserved);
            }
            if (!releaseByGrant.isEmpty()) {
                throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
            }
        }
        return allocations;
    }
}
