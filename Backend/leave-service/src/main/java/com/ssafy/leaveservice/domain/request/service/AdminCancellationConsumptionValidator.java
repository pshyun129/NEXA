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
public class AdminCancellationConsumptionValidator {
    private final LeaveRequestAllocationRepository allocations;
    private final LeaveLedgerRepository ledger;
    private final LeaveAccountRepository accounts;

    @Transactional(propagation = Propagation.MANDATORY)
    public List<LeaveLedger> findRestorableConsumes(LeaveRequest request, OffsetDateTime asOf, boolean completed) {
        // 신청에 저장된 날짜·배분과 모든 관련 원장 조회
        List<LeaveRequestAllocation> parts = allocations.findAllByRequestIdOrderByAllocationIdAsc(request.getRequestId());
        List<LeaveLedger> entries = ledger.findAllByLeaveReqIdOrderByEntryIdAsc(request.getRequestId());
        Map<Long, List<LeaveLedger>> byAllocation = new HashMap<>();
        for (LeaveLedger entry : entries) {
            if (entry.getAllocationId() == null || entry.getCreatedAt().isAfter(asOf)) {
                throw invalid();
            }
            byAllocation.computeIfAbsent(entry.getAllocationId(), ignored -> new ArrayList<>()).add(entry);
        }
        List<LeaveLedger> consumes = new ArrayList<>();
        List<Long> originalIds = new ArrayList<>();
        Map<Long, Long> byDate = new HashMap<>();
        Map<Long, Long> byGrant = new HashMap<>();
        long total = 0;
        try {
            // 배분마다 정확히 RESERVE·CONSUME 한 쌍을 확인하고 복원 원본을 결정
            for (LeaveRequestAllocation part : parts) {
                if (!request.getRequestId().equals(part.getRequestId())
                        || !request.getEmployeeId().equals(part.getEmployeeId()) || part.getAllocatedMinutes() <= 0) {
                    throw invalid();
                }
                List<LeaveLedger> history = byAllocation.remove(part.getAllocationId());
                if (history == null || history.size() != (completed ? 3 : 2)) {
                    throw invalid();
                }
                LeaveLedger reserve = null;
                LeaveLedger consume = null;
                LeaveLedger restore = null;
                for (LeaveLedger entry : history) {
                    if (!part.getAccountId().equals(entry.getAccount().getAccountId())
                            || !part.getGrantId().equals(entry.getGrantId())) {
                        throw invalid();
                    }
                    if (entry.getEventType() == LedgerEventType.RESERVE && reserve == null
                            && entry.getQuantityDelta() == 0 && entry.getReservedDelta() == part.getAllocatedMinutes()
                            && entry.getReversesEntryId() == null) {
                        reserve = entry;
                    } else if (entry.getEventType() == LedgerEventType.CONSUME && consume == null
                            && entry.getQuantityDelta() == -part.getAllocatedMinutes()
                            && entry.getReservedDelta() == -part.getAllocatedMinutes() && entry.getReversesEntryId() == null) {
                        consume = entry;
                    } else if (completed && entry.getEventType() == LedgerEventType.RESTORE && restore == null) {
                        restore = entry;
                    } else {
                        throw invalid();
                    }
                }
                if (reserve == null || consume == null) {
                    throw invalid();
                }
                if (completed && (restore == null || restore.getQuantityDelta() != part.getAllocatedMinutes()
                        || restore.getReservedDelta() != 0 || !consume.getEntryId().equals(restore.getReversesEntryId())
                        || !Objects.equals(request.getAdminCancelRequestedBy(), restore.getActorEmpId())
                        || !("leave:admin-restore:consume:" + consume.getEntryId()).equals(restore.getIdempotencyKey()))) {
                    throw invalid();
                }
                originalIds.add(reserve.getEntryId());
                originalIds.add(consume.getEntryId());
                consumes.add(consume);
                byDate.merge(part.getRequestDateId(), (long) part.getAllocatedMinutes(), Math::addExact);
                byGrant.merge(part.getGrantId(), (long) part.getAllocatedMinutes(), Math::addExact);
                total = Math.addExact(total, part.getAllocatedMinutes());
            }
            for (LeaveRequestDate date : request.getDates()) {
                if (byDate.getOrDefault(date.getRequestDateId(), 0L) != date.getDeductMinutes()) {
                    throw invalid();
                }
                byDate.remove(date.getRequestDateId());
            }
            if (total != request.getTotalDeductMinutes() || !byDate.isEmpty() || !byAllocation.isEmpty()) {
                throw invalid();
            }
        } catch (ArithmeticException failure) {
            throw invalid();
        }

        // 기존 역분개나 부분 복원이 있으면 전체 취소를 임의로 보정하지 않는다.
        if (!completed && !originalIds.isEmpty() && ledger.existsByReversesEntryIdIn(originalIds)) {
            throw invalid();
        }
        if (completed && !consumes.isEmpty()) {
            List<Long> reserveIds = entries.stream().filter(entry -> entry.getEventType() == LedgerEventType.RESERVE)
                    .map(LeaveLedger::getEntryId).toList();
            if (ledger.existsByReversesEntryIdIn(reserveIds)) {
                throw invalid();
            }
        }

        // 만료 여부와 관계없이 원래 발생분의 현재 정합성과 복원 후 Long 범위를 검증
        if (!byGrant.isEmpty()) {
            for (PreviewGrantBalance balance : accounts.findRecordedGrantBalances(request.getEmployeeId(), asOf)) {
                Long minutes = byGrant.remove(balance.getGrantId());
                if (minutes == null) {
                    continue;
                }
                LeaveBalanceAmounts amounts = LeaveBalanceAmounts.from(balance.getQuantityMinutes(), balance.getReservedMinutes());
                if (!completed) {
                    LeaveBalanceAmounts.from(BigDecimal.valueOf(amounts.quantityMinutes()).add(BigDecimal.valueOf(minutes)),
                            BigDecimal.valueOf(amounts.reservedMinutes()));
                }
            }
            if (!byGrant.isEmpty()) {
                throw invalid();
            }
        }
        return consumes;
    }

    private BusinessException invalid() {
        return new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
    }
}
