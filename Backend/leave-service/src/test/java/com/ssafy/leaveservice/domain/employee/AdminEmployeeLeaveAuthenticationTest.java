package com.ssafy.leaveservice.domain.employee;

import com.ssafy.leaveservice.domain.account.repository.LeaveAccountRepository;
import com.ssafy.leaveservice.domain.account.service.MyLeaveBalanceService;
import com.ssafy.leaveservice.domain.employee.controller.AdminEmployeeLeaveQueryController;
import com.ssafy.leaveservice.domain.grant.repository.LeaveGrantRepository;
import com.ssafy.leaveservice.domain.grant.service.MyLeaveGrantService;
import com.ssafy.leaveservice.domain.ledger.repository.LeaveLedgerRepository;
import com.ssafy.leaveservice.domain.ledger.service.MyLeaveLedgerService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.auth.TokenEmployeeAccess;
import com.ssafy.leaveservice.global.exception.GlobalExceptionHandler;
import com.ssafy.leaveservice.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminEmployeeLeaveAuthenticationTest {

    private final com.ssafy.leaveservice.global.auth.LeaveAdministratorAccess administratorAccess =
            mock(com.ssafy.leaveservice.global.auth.LeaveAdministratorAccess.class);
    private final EmployeeAccess tokenAccess = new TokenEmployeeAccess(administratorAccess);
    private final LeaveAccountRepository accountRepository = mock(LeaveAccountRepository.class);
    private final LeaveGrantRepository grantRepository = mock(LeaveGrantRepository.class);
    private final LeaveLedgerRepository ledgerRepository = mock(LeaveLedgerRepository.class);

    @AfterEach
    void clearToken() {
        TestTokens.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"balances", "grants", "ledger"})
    void userTokenCannotQueryOtherEmployeesEvenWithSpoofedAdminHeaders(String endpoint) throws Exception {
        TestTokens.authenticate(1L, "USER");
        endpoint(tokenAccess).perform(get("/api/v1/leaves/admin/employees/42/" + endpoint)
                        .header("X-Employee-Id", "1").header("X-Role", "ADMIN"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("AUTH_009"));
        verifyNoInteractions(accountRepository, grantRepository, ledgerRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"balances", "grants", "ledger"})
    void adminTokenQueriesPathTargetInsteadOfTokenEmployee(String endpoint) throws Exception {
        TestTokens.authenticate(1L, "USER");
        when(administratorAccess.isAdministrator(1L)).thenReturn(true);
        endpoint(tokenAccess).perform(get("/api/v1/leaves/admin/employees/42/" + endpoint))
                .andExpect(status().isOk()).andExpect(content().json("{\"success\":true,\"error\":null,\"data\":[]}"));
        switch (endpoint) {
            case "balances" -> verify(accountRepository).findBalancesByEmployeeId(eq(42L), any());
            case "grants" -> verify(grantRepository).findAllWithAccountAndBalanceTypeByEmployeeId(42L);
            case "ledger" -> verify(ledgerRepository).findAllWithAccountAndBalanceTypeByEmployeeId(42L);
            default -> throw new AssertionError(endpoint);
        }
        verify(accountRepository, never()).findBalancesByEmployeeId(eq(1L), any());
        verify(grantRepository, never()).findAllWithAccountAndBalanceTypeByEmployeeId(1L);
        verify(ledgerRepository, never()).findAllWithAccountAndBalanceTypeByEmployeeId(1L);
    }

    private MockMvc endpoint(EmployeeAccess employeeAccess) {
        AdminEmployeeLeaveQueryController controller = new AdminEmployeeLeaveQueryController(
                new MyLeaveBalanceService(accountRepository, employeeAccess),
                new MyLeaveGrantService(grantRepository, employeeAccess),
                new MyLeaveLedgerService(ledgerRepository, employeeAccess)
        );
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }
}
