package com.ssafy.leaveservice.domain.account;

import com.ssafy.leaveservice.domain.account.controller.MyLeaveBalanceController;
import com.ssafy.leaveservice.domain.account.repository.LeaveAccountRepository;
import com.ssafy.leaveservice.domain.account.service.MyLeaveBalanceService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.auth.TokenEmployeeAccess;
import com.ssafy.leaveservice.global.exception.GlobalExceptionHandler;
import com.ssafy.leaveservice.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MyLeaveBalanceAuthenticationTest {

    private final EmployeeAccess tokenAccess = new TokenEmployeeAccess(org.mockito.Mockito.mock(com.ssafy.leaveservice.global.auth.LeaveAdministratorAccess.class));

    @AfterEach
    void clearToken() {
        TestTokens.clear();
    }

    @Test
    void withoutTokenSpoofedIdentityIsUnauthorized() throws Exception {
        LeaveAccountRepository repository = mock(LeaveAccountRepository.class);
        endpoint(repository, tokenAccess)
                .perform(get("/api/v1/leaves/me/balances").param("employeeId", "84").header("X-Employee-Id", "84"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("AUTH_001"));
        verifyNoInteractions(repository);
    }

    @Test
    void tokenEmployeeIsUsedDespiteSpoofedIdentity() throws Exception {
        TestTokens.authenticate(1L, "USER");
        LeaveAccountRepository repository = mock(LeaveAccountRepository.class);
        endpoint(repository, tokenAccess)
                .perform(get("/api/v1/leaves/me/balances").param("employeeId", "84").header("X-Employee-Id", "84"))
                .andExpect(status().isOk()).andExpect(content().json(
                        "{\"success\":true,\"error\":null,\"data\":[]}", JsonCompareMode.STRICT));
        verify(repository).findBalancesByEmployeeId(eq(1L), any());
        verify(repository, never()).findBalancesByEmployeeId(eq(84L), any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void invalidAuthenticatedIdReturnsExistingUnauthorizedResponse(Long employeeId) throws Exception {
        EmployeeAccess employeeAccess = mock(EmployeeAccess.class);
        LeaveAccountRepository repository = mock(LeaveAccountRepository.class);
        when(employeeAccess.currentEmployeeId()).thenReturn(employeeId);
        endpoint(repository, employeeAccess).perform(get("/api/v1/leaves/me/balances"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("AUTH_001"));
        verifyNoInteractions(repository);
    }

    private MockMvc endpoint(LeaveAccountRepository repository, EmployeeAccess employeeAccess) {
        return MockMvcBuilders.standaloneSetup(new MyLeaveBalanceController(new MyLeaveBalanceService(repository, employeeAccess)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
}
