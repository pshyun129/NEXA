package com.ssafy.leaveservice.domain.grant.service;

import com.ssafy.leaveservice.client.hr.HrClient;
import com.ssafy.leaveservice.domain.grant.dto.ManualLeaveGrantRequest;
import com.ssafy.leaveservice.domain.grant.dto.ManualLeaveGrantRequest.GrantMode;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ManualLeaveGrantServiceTest {
    private final EmployeeAccess access = mock(EmployeeAccess.class);
    private final HrClient hr = mock(HrClient.class);
    private final ManualLeaveGrantTransactionService transactions = mock(ManualLeaveGrantTransactionService.class);
    private ValidatorFactory factory;
    private ManualLeaveGrantService service;
    private final OffsetDateTime start = OffsetDateTime.parse("2026-01-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        service = new ManualLeaveGrantService(access, hr, factory.getValidator(), transactions);
        when(access.currentEmployeeId()).thenReturn(10L);
        when(access.isAdmin(10L)).thenReturn(true);
        when(hr.isAvailable()).thenReturn(true);
        when(hr.findEmployee(42L)).thenReturn(new HrClient.EmployeeInfo(42L, 1L, 1L, null, null, HrClient.EmploymentStatus.EMPLOYED));
    }

    @AfterEach
    void close() { factory.close(); }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "a b", "한글", "a\nb"})
    void invalidKeyStopsBeforeHrAndTransaction(String key) {
        assertError(() -> service.grant(42L, key, request(480, "지급")), ErrorCode.INVALID_INPUT);
        verifyNoInteractions(hr, transactions);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void quantityMustBePositive(int minutes) {
        assertError(() -> service.grant(42L, "key", request(minutes, "지급")), ErrorCode.INVALID_INPUT);
        verifyNoInteractions(transactions);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n"})
    void reasonMustBePresent(String reason) {
        assertError(() -> service.grant(42L, "key", request(480, reason)), ErrorCode.INVALID_INPUT);
    }

    @Test
    void rejectsOversizedReasonKeyAndReversedValidity() {
        assertError(() -> service.grant(42L, "key", request(480, "a".repeat(501))), ErrorCode.INVALID_INPUT);
        assertError(() -> service.grant(42L, "a".repeat(129), request(480, "지급")), ErrorCode.INVALID_INPUT);
        ManualLeaveGrantRequest reversed = new ManualLeaveGrantRequest(1L, GrantMode.INITIAL, 480, start, start, "지급");
        assertError(() -> service.grant(42L, "key", reversed), ErrorCode.INVALID_INPUT);
    }

    @Test
    void deniesUnauthorizedOrNonAdministrator() {
        when(access.currentEmployeeId()).thenReturn(null);
        assertError(() -> service.grant(42L, "key", request(480, "지급")), ErrorCode.UNAUTHORIZED);
        when(access.currentEmployeeId()).thenReturn(10L);
        when(access.isAdmin(10L)).thenReturn(false);
        assertError(() -> service.grant(42L, "key", request(480, "지급")), ErrorCode.ACCESS_DENIED);
        verifyNoInteractions(transactions);
    }

    @Test
    void hrUnavailableOrIdentityMismatchCannotWrite() {
        when(hr.isAvailable()).thenReturn(false);
        assertError(() -> service.grant(42L, "key", request(480, "지급")), ErrorCode.HR_VERIFICATION_UNAVAILABLE);
        when(hr.isAvailable()).thenReturn(true);
        when(hr.findEmployee(42L)).thenReturn(new HrClient.EmployeeInfo(43L, 1L, 1L, null, null, HrClient.EmploymentStatus.EMPLOYED));
        assertError(() -> service.grant(42L, "key", request(480, "지급")), ErrorCode.HR_VERIFICATION_UNAVAILABLE);
        verifyNoInteractions(transactions);
    }

    @Test
    void preservesReasonAndNormalizesOnlyDatabaseTimePrecision() {
        ManualLeaveGrantRequest request = new ManualLeaveGrantRequest(1L, GrantMode.INITIAL, 480,
                start.withNano(123456789), null, " 지급 ");
        service.grant(42L, "key", request);
        verify(transactions).grant(eq(42L), eq(10L), eq("key"), argThat(normalized ->
                normalized.reason().equals(" 지급 ") && normalized.expiresOn() == null
                        && normalized.validFrom().getNano() == 123456000));
    }

    private ManualLeaveGrantRequest request(int minutes, String reason) {
        return new ManualLeaveGrantRequest(1L, GrantMode.INITIAL, minutes, start, start.plusYears(1), reason);
    }

    private void assertError(Runnable action, ErrorCode error) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(error));
    }
}
