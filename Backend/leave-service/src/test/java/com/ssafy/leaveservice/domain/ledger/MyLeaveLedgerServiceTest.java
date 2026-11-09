package com.ssafy.leaveservice.domain.ledger;

import com.ssafy.leaveservice.domain.account.entity.LeaveAccount;
import com.ssafy.leaveservice.domain.ledger.dto.MyLeaveLedgerResponse;
import com.ssafy.leaveservice.domain.ledger.entity.LeaveLedger;
import com.ssafy.leaveservice.domain.ledger.entity.LedgerEventType;
import com.ssafy.leaveservice.domain.ledger.repository.LeaveLedgerRepository;
import com.ssafy.leaveservice.domain.ledger.service.MyLeaveLedgerService;
import com.ssafy.leaveservice.domain.leavetype.entity.LeaveBalanceType;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MyLeaveLedgerServiceTest {

    private final LeaveLedgerRepository leaveLedgerRepository = mock(LeaveLedgerRepository.class);
    private final EmployeeAccess employeeAccess = mock(EmployeeAccess.class);
    private final MyLeaveLedgerService service = new MyLeaveLedgerService(leaveLedgerRepository, employeeAccess);

    @BeforeEach
    void setUp() {
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
    }

    @Test
    void mapsAllStoredFieldsAndSignedDeltasWithoutCheckingAdminOrCurrentPolicy() {
        LeaveBalanceType balanceType = mock(LeaveBalanceType.class);
        when(balanceType.getBalanceTypeId()).thenReturn(2L);
        when(balanceType.getName()).thenReturn("보상");
        LeaveAccount account = mock(LeaveAccount.class);
        when(account.getAccountId()).thenReturn(902L);
        when(account.getBalanceType()).thenReturn(balanceType);
        LeaveLedger ledgerEntry = mock(LeaveLedger.class);
        OffsetDateTime createdAt = OffsetDateTime.parse("2020-01-01T09:00:00+09:00");
        when(ledgerEntry.getEntryId()).thenReturn(101L);
        when(ledgerEntry.getAccount()).thenReturn(account);
        when(ledgerEntry.getGrantId()).thenReturn(12L);
        when(ledgerEntry.getLeaveReqId()).thenReturn(71L);
        when(ledgerEntry.getReversesEntryId()).thenReturn(100L);
        when(ledgerEntry.getEventType()).thenReturn(LedgerEventType.ADJUST);
        when(ledgerEntry.getQuantityDelta()).thenReturn(-120);
        when(ledgerEntry.getReservedDelta()).thenReturn(0);
        when(ledgerEntry.getReason()).thenReturn("  지급 조정  ");
        when(ledgerEntry.getCreatedAt()).thenReturn(createdAt);
        when(ledgerEntry.getIdempotencyKey()).thenReturn("adjust-101");
        when(leaveLedgerRepository.findAllWithAccountAndBalanceTypeByEmployeeId(42L)).thenReturn(List.of(ledgerEntry));

        assertThat(service.getMyLedger()).containsExactly(new MyLeaveLedgerResponse(
                101L, 902L, 2L, "보상", 12L, 71L, 100L, LedgerEventType.ADJUST,
                -120, 0, "  지급 조정  ", createdAt, "adjust-101"
        ));
        verify(leaveLedgerRepository).findAllWithAccountAndBalanceTypeByEmployeeId(42L);
        verifyNoMoreInteractions(leaveLedgerRepository);
        verify(employeeAccess, never()).isAdmin(anyLong());
    }

    @Test
    void returnsEmptyListWhenEmployeeHasNoLedger() {
        assertThat(service.getMyLedger()).isEmpty();
        verify(leaveLedgerRepository).findAllWithAccountAndBalanceTypeByEmployeeId(42L);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void invalidAuthenticatedIdentityDoesNotReadDatabase(Long employeeId) {
        when(employeeAccess.currentEmployeeId()).thenReturn(employeeId);
        assertError(ErrorCode.UNAUTHORIZED);
        verifyNoInteractions(leaveLedgerRepository);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"UNAUTHORIZED", "AUTHENTICATION_UNAVAILABLE"})
    void propagatesExistingAuthenticationFailures(ErrorCode errorCode) {
        when(employeeAccess.currentEmployeeId()).thenThrow(new BusinessException(errorCode));
        assertError(errorCode);
        verifyNoInteractions(leaveLedgerRepository);
    }

    private void assertError(ErrorCode errorCode) {
        assertThatThrownBy(service::getMyLedger).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
        verify(employeeAccess, never()).isAdmin(anyLong());
    }
}
