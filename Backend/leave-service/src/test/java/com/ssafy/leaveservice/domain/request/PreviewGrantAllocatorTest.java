package com.ssafy.leaveservice.domain.request;

import com.ssafy.leaveservice.domain.account.repository.projection.PreviewGrantBalance;
import com.ssafy.leaveservice.domain.request.dto.LeaveRequestPreviewRequest.DateRequest;
import com.ssafy.leaveservice.domain.request.dto.LeaveRequestPreviewResponse.*;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestPortion;
import com.ssafy.leaveservice.domain.request.service.PreviewGrantAllocator;
import com.ssafy.leaveservice.global.exception.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreviewGrantAllocatorTest {
    private final PreviewGrantAllocator allocator = new PreviewGrantAllocator();
    private static final LocalDate DAY = LocalDate.of(2030, 1, 7);

    @ParameterizedTest
    @CsvSource({"FULL,480", "AM_HALF,240", "PM_HALF,240"})
    void fixedMinutes(LeaveRequestPortion portion, int expected) {
        assertThat(PreviewGrantAllocator.requestedMinutes(portion)).isEqualTo(expected);
    }

    @Test
    void fefoUsesExpiryThenValidFromThenIdAndNullExpiryLast() {
        List<PreviewGrantBalance> grants = List.of(
                grant(9, null, "2020-01-01T00:00:00Z", 1000, 0),
                grant(3, "2030-02-01T00:00:00Z", "2021-01-01T00:00:00Z", 100, 0),
                grant(2, "2030-02-01T00:00:00Z", "2020-01-01T00:00:00Z", 100, 0),
                grant(1, "2030-02-01T00:00:00Z", "2020-01-01T00:00:00Z", 100, 0));
        var result = allocator.allocate(List.of(date(DAY, LeaveRequestPortion.FULL)), grants);
        assertThat(result.reasons()).isEmpty();
        assertThat(result.dates().getFirst().allocations()).containsExactly(
                new Allocation(1L,100), new Allocation(2L,100), new Allocation(3L,100), new Allocation(9L,180));
    }

    @Test
    void chronologicalDatesDoNotReuseGrantQuantityAndAlreadyReservedIsExcluded() {
        var result = allocator.allocate(List.of(date(DAY.plusDays(1), LeaveRequestPortion.FULL),
                        date(DAY, LeaveRequestPortion.AM_HALF)),
                List.of(grant(1,null,"2020-01-01T00:00:00Z",600,120)));
        assertThat(result.dates().getFirst().leaveDate()).isEqualTo(DAY);
        assertThat(result.dates().getFirst().allocations()).containsExactly(new Allocation(1L,240));
        assertThat(result.dates().getLast().allocations()).containsExactly(new Allocation(1L,240));
        assertThat(result.reasons()).extracting(Reason::code).containsExactly("INSUFFICIENT_BALANCE");
    }

    @Test
    void recordedFutureGrantIsAvailableOnlyOnValidDays() {
        var future = grant(2,null,"2030-01-08T00:00:00+09:00",480,0);
        var result = allocator.allocate(List.of(date(DAY,LeaveRequestPortion.FULL),
                date(DAY.plusDays(1),LeaveRequestPortion.FULL)), List.of(future));
        assertThat(result.reasons()).extracting(Reason::date).containsExactly(DAY);
        assertThat(result.dates().getLast().allocations()).containsExactly(new Allocation(2L,480));
    }

    @ParameterizedTest
    @CsvSource({
            "2030-01-07T00:00:00+09:00,2030-01-08T00:00:00+09:00,true",
            "2030-01-07T01:00:00+09:00,2030-01-09T00:00:00+09:00,false",
            "2020-01-01T00:00:00Z,2030-01-07T12:00:00+09:00,false"
    })
    void fullDayOrIntradayBoundary(String start, String end, boolean verified) {
        var result = allocator.allocate(List.of(date(DAY,LeaveRequestPortion.FULL)),List.of(grant(1,end,start,480,0)));
        assertThat(result.reasons().isEmpty()).isEqualTo(verified);
        if (!verified) {
            assertThat(result.reasons()).extracting(Reason::code).containsExactly("GRANT_VALIDITY_BOUNDARY");
        }
    }

    @Test
    void expiredAtDayStartIsExcludedAndFutureAtNextDayIsExcluded() {
        var result = allocator.allocate(List.of(date(DAY,LeaveRequestPortion.FULL)), List.of(
                grant(1,"2030-01-07T00:00:00+09:00","2020-01-01T00:00:00Z",480,0),
                grant(2,null,"2030-01-08T00:00:00+09:00",480,0)));
        assertThat(result.dates().getFirst().allocations()).isEmpty();
        assertThat(result.reasons()).extracting(Reason::code).containsExactly("INSUFFICIENT_BALANCE");
    }

    @ParameterizedTest
    @CsvSource({"-1,0", "10,-1", "10,11", "9223372036854775808,0"})
    void relevantGrantAccountingErrorsRejectWholeCalculation(String quantity, String reserved) {
        PreviewGrantBalance grant = grant(1,null,"2020-01-01T00:00:00Z",0,0);
        when(grant.getQuantityMinutes()).thenReturn(new BigDecimal(quantity));
        when(grant.getReservedMinutes()).thenReturn(new BigDecimal(reserved));
        assertThatThrownBy(() -> allocator.allocate(List.of(date(DAY,LeaveRequestPortion.FULL)), List.of(grant)))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.DATA_INTEGRITY_VIOLATION));
    }

    @Test
    void sumOverflowIs409AndInvalidExpiredGrantDoesNotOffsetValidGrant() {
        PreviewGrantBalance huge = grant(1,null,"2020-01-01T00:00:00Z",Long.MAX_VALUE,0);
        PreviewGrantBalance extra = grant(2,null,"2020-01-01T00:00:00Z",1,0);
        assertThatThrownBy(() -> allocator.allocate(List.of(date(DAY,LeaveRequestPortion.FULL)),List.of(huge,extra)))
                .isInstanceOf(BusinessException.class);
        PreviewGrantBalance expired = grant(3,"2020-02-01T00:00:00Z","2020-01-01T00:00:00Z",-10,0);
        assertThat(allocator.allocate(List.of(date(DAY,LeaveRequestPortion.FULL)),List.of(huge,expired)).reasons()).isEmpty();
    }

    static DateRequest date(LocalDate day, LeaveRequestPortion portion) {
        return new DateRequest(day,portion);
    }

    static PreviewGrantBalance grant(long id, String expiry, String valid, long quantity, long reserved) {
        PreviewGrantBalance grant = mock(PreviewGrantBalance.class);
        when(grant.getGrantId()).thenReturn(id);
        when(grant.getValidFrom()).thenReturn(OffsetDateTime.parse(valid));
        when(grant.getExpiresOn()).thenReturn(expiry == null ? null : OffsetDateTime.parse(expiry));
        when(grant.getQuantityMinutes()).thenReturn(BigDecimal.valueOf(quantity));
        when(grant.getReservedMinutes()).thenReturn(BigDecimal.valueOf(reserved));
        return grant;
    }
}
