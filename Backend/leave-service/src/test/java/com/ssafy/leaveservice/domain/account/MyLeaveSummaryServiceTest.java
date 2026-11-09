package com.ssafy.leaveservice.domain.account;

import com.ssafy.leaveservice.domain.account.dto.MyLeaveBalanceResponse;
import com.ssafy.leaveservice.domain.account.dto.MyLeaveSummaryResponse;
import com.ssafy.leaveservice.domain.account.repository.LeaveAccountRepository;
import com.ssafy.leaveservice.domain.account.repository.projection.LeaveAccountBalance;
import com.ssafy.leaveservice.domain.account.service.MyLeaveBalanceService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MyLeaveSummaryServiceTest {

    private final LeaveAccountRepository repository = mock(LeaveAccountRepository.class);
    private final EmployeeAccess employeeAccess = mock(EmployeeAccess.class);
    private final MyLeaveBalanceService service = new MyLeaveBalanceService(repository, employeeAccess);

    @Test
    void responseUsesExactlyTheSameCutoffObjectPassedToExistingRepository() {
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        LeaveAccountBalance balance = mock(LeaveAccountBalance.class);
        when(balance.getAccountId()).thenReturn(901L);
        when(balance.getBalanceTypeId()).thenReturn(1L);
        when(balance.getBalanceTypeName()).thenReturn("연차");
        when(balance.getQuantityMinutes()).thenReturn(new BigDecimal("480"));
        when(balance.getReservedMinutes()).thenReturn(new BigDecimal("120"));
        when(repository.findBalancesByEmployeeId(eq(42L), any())).thenReturn(List.of(balance));
        OffsetDateTime serverTime = OffsetDateTime.parse("2030-01-01T09:00:00.123456789+09:00");
        try (MockedStatic<OffsetDateTime> time = mockStatic(OffsetDateTime.class, CALLS_REAL_METHODS)) {
            time.when(OffsetDateTime::now).thenReturn(serverTime);
            time.clearInvocations();

            MyLeaveSummaryResponse response = service.getMySummary();

            ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
            verify(repository).findBalancesByEmployeeId(eq(42L), cutoff.capture());
            assertThat(response.asOf()).isSameAs(cutoff.getValue()).isEqualTo(serverTime.truncatedTo(ChronoUnit.MICROS));
            assertThat(response.balances()).containsExactly(new MyLeaveBalanceResponse(901L, 1L, "연차", 480, 120, 360));
            time.verify(OffsetDateTime::now, times(1));
        }
        verifyNoMoreInteractions(repository);
        verify(employeeAccess, never()).isAdmin(anyLong());
    }

    @Test
    void noAccountsStillReturnsActualCutoffAndEmptyBalances() {
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        MyLeaveSummaryResponse response = service.getMySummary();
        assertThat(response.asOf()).isNotNull();
        assertThat(response.balances()).isEmpty();
        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repository).findBalancesByEmployeeId(eq(42L), cutoff.capture());
        assertThat(response.asOf()).isSameAs(cutoff.getValue());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void invalidAuthenticatedIdDoesNotQuery(Long employeeId) {
        when(employeeAccess.currentEmployeeId()).thenReturn(employeeId);
        assertError(ErrorCode.UNAUTHORIZED);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"UNAUTHORIZED", "AUTHENTICATION_UNAVAILABLE"})
    void existingAuthenticationErrorsArePreserved(ErrorCode errorCode) {
        when(employeeAccess.currentEmployeeId()).thenThrow(new BusinessException(errorCode));
        assertError(errorCode);
        verifyNoInteractions(repository);
    }

    @Test
    void usesExistingInvalidGrantConflict() {
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        LeaveAccountBalance invalid = mock(LeaveAccountBalance.class);
        when(invalid.getInvalidGrant()).thenReturn(true);
        when(repository.findBalancesByEmployeeId(eq(42L), any())).thenReturn(List.of(invalid));
        assertError(ErrorCode.DATA_INTEGRITY_VIOLATION);
    }

    @Test
    void usesExistingExactLongOverflowConflict() {
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        LeaveAccountBalance overflow = mock(LeaveAccountBalance.class);
        when(overflow.getQuantityMinutes()).thenReturn(new BigDecimal("9223372036854775808"));
        when(repository.findBalancesByEmployeeId(eq(42L), any())).thenReturn(List.of(overflow));
        assertError(ErrorCode.DATA_INTEGRITY_VIOLATION);
    }

    private void assertError(ErrorCode errorCode) {
        assertThatThrownBy(service::getMySummary).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
