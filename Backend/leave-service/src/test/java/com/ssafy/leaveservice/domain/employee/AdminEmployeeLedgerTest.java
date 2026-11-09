package com.ssafy.leaveservice.domain.employee;

import com.ssafy.leaveservice.domain.account.service.MyLeaveBalanceService;
import com.ssafy.leaveservice.domain.employee.controller.AdminEmployeeLeaveQueryController;
import com.ssafy.leaveservice.domain.grant.service.MyLeaveGrantService;
import com.ssafy.leaveservice.domain.ledger.entity.LeaveLedger;
import com.ssafy.leaveservice.domain.ledger.repository.LeaveLedgerRepository;
import com.ssafy.leaveservice.domain.ledger.service.MyLeaveLedgerService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import com.ssafy.leaveservice.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminEmployeeLedgerTest {

    private final LeaveLedgerRepository repository = mock(LeaveLedgerRepository.class);
    private final EmployeeAccess employeeAccess = mock(EmployeeAccess.class);
    private final MyLeaveLedgerService service = new MyLeaveLedgerService(repository, employeeAccess);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(employeeAccess.currentEmployeeId()).thenReturn(7L);
        when(employeeAccess.isAdmin(7L)).thenReturn(true);
        mvc = MockMvcBuilders.standaloneSetup(new AdminEmployeeLeaveQueryController(
                        mock(MyLeaveBalanceService.class), mock(MyLeaveGrantService.class), service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void usesTargetEmployeeAndPreservesSignedLedgerDeltas() throws Exception {
        LeaveLedger ledgerEntry = mock(LeaveLedger.class, RETURNS_DEEP_STUBS);
        when(ledgerEntry.getEntryId()).thenReturn(101L);
        when(ledgerEntry.getGrantId()).thenReturn(8L);
        when(ledgerEntry.getQuantityDelta()).thenReturn(-120);
        when(ledgerEntry.getReservedDelta()).thenReturn(-60);
        when(ledgerEntry.getAccount().getAccountId()).thenReturn(901L);
        when(repository.findAllWithAccountAndBalanceTypeByEmployeeId(42L)).thenReturn(List.of(ledgerEntry));
        mvc.perform(get("/api/v1/leaves/admin/employees/42/ledger"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].quantityDelta").value(-120))
                .andExpect(jsonPath("$.data[0].reservedDelta").value(-60));
        verify(repository).findAllWithAccountAndBalanceTypeByEmployeeId(42L);
        verifyNoMoreInteractions(repository);
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        assertThat(service.getMyLedger().getFirst().quantityDelta()).isEqualTo(-120);
        verify(employeeAccess, never()).isAdmin(42L);
    }

    @Test
    void missingAccountReturnsEmptyApiResult() throws Exception {
        mvc.perform(get("/api/v1/leaves/admin/employees/42/ledger"))
                .andExpect(status().isOk()).andExpect(content().json("{\"success\":true,\"error\":null,\"data\":[]}"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void serviceRejectsInvalidTargetAfterCheckingAdmin(Long employeeId) {
        assertError(() -> service.getEmployeeLedger(employeeId), ErrorCode.INVALID_INPUT);
        verify(employeeAccess).isAdmin(7L);
        verifyNoInteractions(repository);
    }

    @Test
    void directServiceCallCannotBypassAdminCheckEvenWithInvalidTarget() throws Exception {
        when(employeeAccess.isAdmin(7L)).thenReturn(false);
        assertError(() -> service.getEmployeeLedger(0L), ErrorCode.ACCESS_DENIED);
        mvc.perform(get("/api/v1/leaves/admin/employees/42/ledger"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("AUTH_009"));
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.2", "9223372036854775808", "0", "-1"})
    void invalidPathReturnsExisting400ApiResult(String employeeId) throws Exception {
        mvc.perform(get("/api/v1/leaves/admin/employees/" + employeeId + "/ledger"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("COMMON_001"));
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"UNAUTHORIZED", "AUTHENTICATION_UNAVAILABLE"})
    void propagatesAuthenticationFailureBeforeTargetQuery(ErrorCode errorCode) {
        when(employeeAccess.currentEmployeeId()).thenThrow(new BusinessException(errorCode));
        assertError(() -> service.getEmployeeLedger(42L), errorCode);
        verifyNoInteractions(repository);
    }

    private void assertError(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
