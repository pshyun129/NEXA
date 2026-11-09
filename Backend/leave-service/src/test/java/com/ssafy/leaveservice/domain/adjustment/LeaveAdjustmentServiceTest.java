package com.ssafy.leaveservice.domain.adjustment;

import com.ssafy.leaveservice.domain.account.entity.LeaveAccount;
import com.ssafy.leaveservice.domain.account.repository.LeaveAccountRepository;
import com.ssafy.leaveservice.domain.account.repository.projection.PreviewGrantBalance;
import com.ssafy.leaveservice.domain.adjustment.dto.LeaveAdjustmentRequest;
import com.ssafy.leaveservice.domain.adjustment.repository.LeaveAdjustmentRepository;
import com.ssafy.leaveservice.domain.adjustment.service.LeaveAdjustmentService;
import com.ssafy.leaveservice.domain.leavetype.entity.LeaveBalanceType;
import com.ssafy.leaveservice.domain.ledger.entity.LeaveLedger;
import com.ssafy.leaveservice.domain.ledger.repository.LeaveLedgerRepository;
import com.ssafy.leaveservice.domain.request.repository.LeaveSubmissionLockRepository;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LeaveAdjustmentServiceTest {

    private static final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    private final EmployeeAccess access = mock(EmployeeAccess.class);
    private final LeaveSubmissionLockRepository locks = mock(LeaveSubmissionLockRepository.class);
    private final LeaveAdjustmentRepository adjustmentRepository = mock(LeaveAdjustmentRepository.class);
    private final LeaveAccountRepository accounts = mock(LeaveAccountRepository.class);
    private final LeaveLedgerRepository ledger = mock(LeaveLedgerRepository.class);
    private final PreviewGrantBalance balance = mock(PreviewGrantBalance.class);
    private final LeaveAccount account = mock(LeaveAccount.class);
    private final OffsetDateTime asOf = OffsetDateTime.parse("2026-11-02T03:00:00Z");
    private LeaveAdjustmentService service;

    @BeforeEach
    void setUp() {
        service = new LeaveAdjustmentService(access, factory.getValidator(), locks, adjustmentRepository, accounts, ledger);
        when(access.currentEmployeeId()).thenReturn(10L);
        when(access.isAdmin(10L)).thenReturn(true);
        when(ledger.findByAdjustmentKey(any())).thenReturn(Optional.empty());
        when(adjustmentRepository.lockOwnedAccount(42L, 901L)).thenReturn(true);
        when(adjustmentRepository.lockOwnedGrant(901L, 1001L)).thenReturn(true);
        when(locks.currentTime()).thenReturn(asOf);
        when(accounts.findById(901L)).thenReturn(Optional.of(account));
        when(account.getAccountId()).thenReturn(901L);
        when(account.getEmployeeId()).thenReturn(42L);
        LeaveBalanceType type = mock(LeaveBalanceType.class);
        when(account.getBalanceType()).thenReturn(type);
        when(type.isActive()).thenReturn(true);
        when(accounts.findAdjustmentGrantBalance(42L, 1001L, asOf)).thenReturn(Optional.of(balance));
        when(balance.getValidFrom()).thenReturn(asOf.minusDays(1));
        when(balance.getQuantityMinutes()).thenReturn(BigDecimal.valueOf(480));
        when(balance.getReservedMinutes()).thenReturn(BigDecimal.ZERO);
        when(adjustmentRepository.hasEffectiveCredit(901L, 1001L, asOf)).thenReturn(true);
        when(ledger.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    @ParameterizedTest
    @CsvSource({"9223372036854775808,0,1", "9223372036854775807,0,1", "-1,0,1", "480,-1,1", "480,481,1"})
    void detectsLongOverflowAndCorruptGrantBeforeInsert(String quantity, String reserved, int delta) {
        when(balance.getQuantityMinutes()).thenReturn(new BigDecimal(quantity));
        when(balance.getReservedMinutes()).thenReturn(new BigDecimal(reserved));
        assertThatThrownBy(() -> service.adjust(42L, "key", request(delta)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.DATA_INTEGRITY_VIOLATION));
        verify(ledger, never()).saveAndFlush(any());
    }

    @Test
    void supportsExactLongBoundaryWithoutOverflowOrIntTruncation() {
        when(balance.getQuantityMinutes()).thenReturn(BigDecimal.valueOf(Long.MAX_VALUE - 1));
        assertThat(service.adjust(42L, "key", request(1)).quantityDelta()).isEqualTo(1);
    }

    @Test
    void usesTargetEmployeeLockAndExactlySameDatabaseTimeForReadAndWrite() {
        service.adjust(42L, "key", request(120));
        org.mockito.InOrder order = inOrder(locks, adjustmentRepository, accounts, ledger);
        order.verify(locks).lockEmployee(42L);
        order.verify(adjustmentRepository).lockRequestKey(any());
        order.verify(ledger).findByAdjustmentKey(any());
        order.verify(adjustmentRepository).lockOwnedAccount(42L, 901L);
        order.verify(adjustmentRepository).lockOwnedGrant(901L, 1001L);
        order.verify(accounts).findById(901L);
        order.verify(locks).currentTime();
        order.verify(accounts).findAdjustmentGrantBalance(42L, 1001L, asOf);
        order.verify(adjustmentRepository).hasEffectiveCredit(901L, 1001L, asOf);
        ArgumentCaptor<LeaveLedger> saved = ArgumentCaptor.forClass(LeaveLedger.class);
        order.verify(ledger).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(asOf);
        assertThat(saved.getValue().getActorEmpId()).isEqualTo(10);
        assertThat(saved.getValue().getReservedDelta()).isZero();
        assertThat(saved.getValue().getReason()).isEqualTo(" 정정 ");
    }

    @ParameterizedTest
    @ValueSource(strings = {"validFrom", "expiresOn"})
    void validityIntervalIsInclusiveStartExclusiveEnd(String boundary) {
        if (boundary.equals("validFrom")) {
            when(balance.getValidFrom()).thenReturn(asOf);
            service.adjust(42L, "key", request(1));
            verify(ledger).saveAndFlush(any());
        } else {
            when(balance.getExpiresOn()).thenReturn(asOf);
            assertThatThrownBy(() -> service.adjust(42L, "key", request(1)))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.LEAVE_ADJUSTMENT_NOT_ALLOWED));
            verify(ledger, never()).saveAndFlush(any());
        }
    }

    @Test
    void replayChecksIdentityBeforeNewEligibilityAndBalanceQueries() {
        LeaveLedger existing = LeaveLedger.adjust(account, 1001L, 120, 10L, " 정정 ", "stored", asOf);
        when(ledger.findByAdjustmentKey(any())).thenReturn(Optional.of(existing));
        assertThat(service.adjust(42L, "key", request(120)).createdAt()).isEqualTo(asOf);
        verify(adjustmentRepository, never()).lockOwnedAccount(any(), any());
        verify(accounts, never()).findAdjustmentGrantBalance(any(), any(), any());
        verify(ledger, never()).saveAndFlush(any());
    }

    @Test
    void denialStopsBeforeLocksOrLedgerQueries() {
        when(access.isAdmin(10L)).thenReturn(false);
        assertThatThrownBy(() -> service.adjust(42L, "key", request(1)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
        verifyNoInteractions(locks, adjustmentRepository);
        verify(ledger, never()).findByAdjustmentKey(any());
    }

    private LeaveAdjustmentRequest request(int delta) {
        return new LeaveAdjustmentRequest(901L, 1001L, delta, " 정정 ");
    }
}
