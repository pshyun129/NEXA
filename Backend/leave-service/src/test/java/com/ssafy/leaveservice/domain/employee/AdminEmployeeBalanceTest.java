package com.ssafy.leaveservice.domain.employee;

import com.ssafy.leaveservice.domain.account.dto.MyLeaveBalanceResponse;
import com.ssafy.leaveservice.domain.account.repository.LeaveAccountRepository;
import com.ssafy.leaveservice.domain.account.repository.projection.LeaveAccountBalance;
import com.ssafy.leaveservice.domain.account.service.MyLeaveBalanceService;
import com.ssafy.leaveservice.domain.employee.controller.AdminEmployeeLeaveQueryController;
import com.ssafy.leaveservice.domain.grant.service.MyLeaveGrantService;
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

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminEmployeeBalanceTest {

    private final LeaveAccountRepository repository = mock(LeaveAccountRepository.class);
    private final EmployeeAccess employeeAccess = mock(EmployeeAccess.class);
    private final MyLeaveBalanceService service = new MyLeaveBalanceService(repository, employeeAccess);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(employeeAccess.currentEmployeeId()).thenReturn(7L);
        when(employeeAccess.isAdmin(7L)).thenReturn(true);
        mvc = MockMvcBuilders.standaloneSetup(new AdminEmployeeLeaveQueryController(
                        service, mock(MyLeaveGrantService.class), mock(MyLeaveLedgerService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void usesTargetEmployeeAndExistingCalculationRatherThanAdminIdentity() throws Exception {
        LeaveAccountBalance balance = mock(LeaveAccountBalance.class);
        when(balance.getAccountId()).thenReturn(901L);
        when(balance.getBalanceTypeId()).thenReturn(1L);
        when(balance.getBalanceTypeName()).thenReturn("연차");
        when(balance.getQuantityMinutes()).thenReturn(new BigDecimal("480"));
        when(balance.getReservedMinutes()).thenReturn(new BigDecimal("120"));
        when(repository.findBalancesByEmployeeId(eq(42L), any())).thenReturn(List.of(balance));
        mvc.perform(get("/api/v1/leaves/admin/employees/42/balances"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].remainingMinutes").value(360));
        verify(repository).findBalancesByEmployeeId(eq(42L), any());
        verifyNoMoreInteractions(repository);
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        assertThat(service.getMyBalances()).containsExactly(new MyLeaveBalanceResponse(901L, 1L, "연차", 480, 120, 360));
        verify(employeeAccess, never()).isAdmin(42L);
    }

    @Test
    void missingAccountReturnsEmptyApiResult() throws Exception {
        mvc.perform(get("/api/v1/leaves/admin/employees/42/balances"))
                .andExpect(status().isOk()).andExpect(content().json("{\"success\":true,\"error\":null,\"data\":[]}"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void serviceRejectsInvalidTargetAfterCheckingAdmin(Long employeeId) {
        assertError(() -> service.getEmployeeBalances(employeeId), ErrorCode.INVALID_INPUT);
        verify(employeeAccess).isAdmin(7L);
        verifyNoInteractions(repository);
    }

    @Test
    void directServiceCallCannotBypassAdminCheckEvenWithInvalidTarget() throws Exception {
        when(employeeAccess.isAdmin(7L)).thenReturn(false);
        assertError(() -> service.getEmployeeBalances(0L), ErrorCode.ACCESS_DENIED);
        mvc.perform(get("/api/v1/leaves/admin/employees/42/balances"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("AUTH_009"));
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.2", "9223372036854775808", "0", "-1"})
    void invalidPathReturnsExisting400ApiResult(String employeeId) throws Exception {
        mvc.perform(get("/api/v1/leaves/admin/employees/" + employeeId + "/balances"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("COMMON_001"));
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"UNAUTHORIZED", "AUTHENTICATION_UNAVAILABLE"})
    void propagatesAuthenticationFailureBeforeTargetQuery(ErrorCode errorCode) {
        when(employeeAccess.currentEmployeeId()).thenThrow(new BusinessException(errorCode));
        assertError(() -> service.getEmployeeBalances(42L), errorCode);
        verifyNoInteractions(repository);
    }

    @Test
    void existingGrantIntegrityConflictIsPreservedForAdmin() throws Exception {
        LeaveAccountBalance invalid = mock(LeaveAccountBalance.class);
        when(invalid.getInvalidGrant()).thenReturn(true);
        when(repository.findBalancesByEmployeeId(eq(42L), any())).thenReturn(List.of(invalid));
        mvc.perform(get("/api/v1/leaves/admin/employees/42/balances"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    void existingLongOverflowConflictIsPreservedForAdmin() throws Exception {
        LeaveAccountBalance overflow = mock(LeaveAccountBalance.class);
        when(overflow.getQuantityMinutes()).thenReturn(new BigDecimal("9223372036854775808"));
        when(repository.findBalancesByEmployeeId(eq(42L), any())).thenReturn(List.of(overflow));
        mvc.perform(get("/api/v1/leaves/admin/employees/42/balances"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    private void assertError(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
