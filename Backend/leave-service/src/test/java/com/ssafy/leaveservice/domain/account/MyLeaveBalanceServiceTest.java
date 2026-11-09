package com.ssafy.leaveservice.domain.account;

import com.ssafy.leaveservice.domain.account.dto.MyLeaveBalanceResponse;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MyLeaveBalanceServiceTest {

    private final LeaveAccountRepository repository = mock(LeaveAccountRepository.class);
    private final EmployeeAccess employeeAccess = mock(EmployeeAccess.class);
    private final MyLeaveBalanceService service = new MyLeaveBalanceService(repository, employeeAccess);

    @Test
    void returnsValidGrantTotalsAndCapturesOneMicrosecondPrecisionCutoff() {
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        LeaveAccountBalance balance = balance("480", "120");
        when(repository.findBalancesByEmployeeId(eq(42L), any())).thenReturn(List.of(balance));
        OffsetDateTime before = OffsetDateTime.now().minusSeconds(1);

        assertThat(service.getMyBalances()).containsExactly(new MyLeaveBalanceResponse(901L, 1L, "연차", 480, 120, 360));

        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repository).findBalancesByEmployeeId(eq(42L), cutoff.capture());
        assertThat(cutoff.getValue()).isAfter(before).isBefore(OffsetDateTime.now().plusSeconds(1));
        assertThat(cutoff.getValue().getNano() % 1000).isZero();
        verifyNoMoreInteractions(repository);
        verify(employeeAccess, never()).isAdmin(anyLong());
    }

    @Test
    void noAccountsReturnsEmptyList() {
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        assertThat(service.getMyBalances()).isEmpty();
    }

    @Test
    void invalidGrantInAnyAccountRejectsEntireRequest() {
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        LeaveAccountBalance validBalance = balance("480", "0");
        LeaveAccountBalance invalidBalance = mock(LeaveAccountBalance.class);
        when(invalidBalance.getInvalidGrant()).thenReturn(true);
        when(repository.findBalancesByEmployeeId(eq(42L), any())).thenReturn(List.of(validBalance, invalidBalance));
        assertError(ErrorCode.DATA_INTEGRITY_VIOLATION);
        verify(invalidBalance, never()).getQuantityMinutes();
    }

    @Test
    void allowsLongMaximumWithoutTruncationOrDoubleConversion() {
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        LeaveAccountBalance balance = balance("9223372036854775807", "1");
        when(repository.findBalancesByEmployeeId(eq(42L), any())).thenReturn(List.of(balance));
        assertThat(service.getMyBalances().getFirst().remainingMinutes()).isEqualTo(Long.MAX_VALUE - 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"9223372036854775808", "18446744073709551616"})
    void numericTotalOutsideLongRangeReturnsConflict(String quantity) {
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        LeaveAccountBalance balance = balance(quantity, "0");
        when(repository.findBalancesByEmployeeId(eq(42L), any())).thenReturn(List.of(balance));
        assertError(ErrorCode.DATA_INTEGRITY_VIOLATION);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void invalidAuthenticatedEmployeeDoesNotQuery(Long employeeId) {
        when(employeeAccess.currentEmployeeId()).thenReturn(employeeId);
        assertError(ErrorCode.UNAUTHORIZED);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"UNAUTHORIZED", "AUTHENTICATION_UNAVAILABLE"})
    void preservesExistingAuthenticationErrors(ErrorCode errorCode) {
        when(employeeAccess.currentEmployeeId()).thenThrow(new BusinessException(errorCode));
        assertError(errorCode);
        verifyNoInteractions(repository);
    }

    private LeaveAccountBalance balance(String quantity, String reserved) {
        LeaveAccountBalance balance = mock(LeaveAccountBalance.class);
        when(balance.getAccountId()).thenReturn(901L);
        when(balance.getBalanceTypeId()).thenReturn(1L);
        when(balance.getBalanceTypeName()).thenReturn("연차");
        when(balance.getQuantityMinutes()).thenReturn(new BigDecimal(quantity));
        when(balance.getReservedMinutes()).thenReturn(new BigDecimal(reserved));
        return balance;
    }

    private void assertError(ErrorCode errorCode) {
        assertThatThrownBy(service::getMyBalances).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
