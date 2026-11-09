package com.ssafy.leaveservice.domain.request.service;

import com.ssafy.leaveservice.domain.account.repository.projection.PreviewGrantBalance;
import com.ssafy.leaveservice.domain.account.service.LeaveBalanceAmounts;
import com.ssafy.leaveservice.domain.request.dto.LeaveRequestPreviewRequest.DateRequest;
import com.ssafy.leaveservice.domain.request.dto.LeaveRequestPreviewResponse.*;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestPortion;
import com.ssafy.leaveservice.global.exception.*;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
public class PreviewGrantAllocator {
    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public AllocationResult allocate(List<DateRequest> dates, List<PreviewGrantBalance> grantBalances) {
        // 신청일과 겹치는 발생분만 검증하고 FEFO 순서로 정렬
        List<PreviewGrantBalance> sortedGrants = new ArrayList<>(grantBalances);
        sortedGrants.sort(Comparator.comparing(PreviewGrantBalance::getExpiresOn,
                        Comparator.nullsLast(Comparator.comparing(OffsetDateTime::toInstant)))
                .thenComparing(grant -> grant.getValidFrom().toInstant()).thenComparing(PreviewGrantBalance::getGrantId));
        List<Candidate> candidates = new ArrayList<>();
        long totalAvailable = 0;
        try {
            for (PreviewGrantBalance grant : sortedGrants) {
                boolean relevant = dates.stream().anyMatch(date -> overlaps(grant, date.leaveDate()));
                if (!relevant) {
                    continue;
                }
                LeaveBalanceAmounts amounts = LeaveBalanceAmounts.from(grant.getQuantityMinutes(), grant.getReservedMinutes());
                totalAvailable = Math.addExact(totalAvailable, amounts.remainingMinutes());
                candidates.add(new Candidate(grant, amounts.remainingMinutes()));
            }
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
        }

        // 날짜 오름차순으로 가상 배분하고 사용한 수량을 다음 날짜에서 제외
        List<DateRequest> sortedDates = dates.stream().sorted(Comparator.comparing(DateRequest::leaveDate)).toList();
        List<DateResponse> responses = new ArrayList<>();
        List<Reason> reasons = new ArrayList<>();
        for (DateRequest date : sortedDates) {
            int requested = requestedMinutes(date.portion());
            long needed = requested;
            boolean boundary = false;
            List<Allocation> allocations = new ArrayList<>();
            for (Candidate candidate : candidates) {
                if (candidate.available == 0 || !overlaps(candidate.grant, date.leaveDate())) {
                    continue;
                }
                if (!coversDay(candidate.grant, date.leaveDate())) {
                    boundary = true;
                    continue;
                }
                long allocated = Math.min(needed, candidate.available);
                if (allocated > 0) {
                    allocations.add(new Allocation(candidate.grant.getGrantId(), allocated));
                    candidate.available -= allocated;
                    needed -= allocated;
                }
            }
            if (boundary) {
                reasons.add(new Reason("GRANT_VALIDITY_BOUNDARY", date.leaveDate(),
                        "발생분 유효기간이 신청일 중간에 변경되어 정확한 적용 시각을 확인해야 합니다."));
            } else if (needed > 0) {
                reasons.add(new Reason("INSUFFICIENT_BALANCE", date.leaveDate(), "해당 날짜에 배분 가능한 휴가 잔액이 부족합니다."));
            }
            responses.add(new DateResponse(date.leaveDate(), date.portion(), requested, requested, List.copyOf(allocations)));
        }
        return new AllocationResult(List.copyOf(responses), List.copyOf(reasons));
    }

    public static int requestedMinutes(LeaveRequestPortion portion) {
        return switch (portion) {
            case FULL -> 480;
            case AM_HALF, PM_HALF -> 240;
            case HOURLY -> throw new BusinessException(ErrorCode.INVALID_INPUT);
        };
    }

    private boolean overlaps(PreviewGrantBalance grant, LocalDate date) {
        Instant start = date.atStartOfDay(SEOUL).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(SEOUL).toInstant();
        return grant.getValidFrom().toInstant().isBefore(end)
                && (grant.getExpiresOn() == null || grant.getExpiresOn().toInstant().isAfter(start));
    }

    private boolean coversDay(PreviewGrantBalance grant, LocalDate date) {
        Instant start = date.atStartOfDay(SEOUL).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(SEOUL).toInstant();
        return !grant.getValidFrom().toInstant().isAfter(start)
                && (grant.getExpiresOn() == null || !grant.getExpiresOn().toInstant().isBefore(end));
    }

    private static class Candidate {
        private final PreviewGrantBalance grant;
        private long available;

        private Candidate(PreviewGrantBalance grant, long available) {
            this.grant = grant;
            this.available = available;
        }
    }

    public record AllocationResult(List<DateResponse> dates, List<Reason> reasons) {}
}
