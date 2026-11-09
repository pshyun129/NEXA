package com.ssafy.leaveservice.domain.ledger;

import com.ssafy.leaveservice.domain.ledger.controller.MyLeaveLedgerController;
import com.ssafy.leaveservice.domain.ledger.repository.LeaveLedgerRepository;
import com.ssafy.leaveservice.domain.ledger.service.MyLeaveLedgerService;
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

class MyLeaveLedgerAuthenticationTest {

    private final EmployeeAccess tokenAccess = new TokenEmployeeAccess(org.mockito.Mockito.mock(com.ssafy.leaveservice.global.auth.LeaveAdministratorAccess.class));

    @AfterEach
    void clearToken() {
        TestTokens.clear();
    }

    @Test
    void withoutTokenSpoofedIdentityIsUnauthorized() throws Exception {
        LeaveLedgerRepository repository = mock(LeaveLedgerRepository.class);
        endpoint(repository, tokenAccess)
                .perform(get("/api/v1/leaves/me/ledger").param("employeeId", "84").header("X-Employee-Id", "84"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("AUTH_001"));
        verifyNoInteractions(repository);
    }

    @Test
    void tokenEmployeeIsUsedDespiteSpoofedIdentity() throws Exception {
        TestTokens.authenticate(1L, "USER");
        LeaveLedgerRepository repository = mock(LeaveLedgerRepository.class);
        endpoint(repository, tokenAccess)
                .perform(get("/api/v1/leaves/me/ledger").param("employeeId", "84").header("X-Employee-Id", "84"))
                .andExpect(status().isOk()).andExpect(content().json(
                        "{\"success\":true,\"error\":null,\"data\":[]}", JsonCompareMode.STRICT));
        verify(repository).findAllWithAccountAndBalanceTypeByEmployeeId(1L);
        verify(repository, never()).findAllWithAccountAndBalanceTypeByEmployeeId(84L);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void invalidAuthenticatedIdReturnsExistingUnauthorizedResponse(Long employeeId) throws Exception {
        EmployeeAccess employeeAccess = mock(EmployeeAccess.class);
        LeaveLedgerRepository repository = mock(LeaveLedgerRepository.class);
        when(employeeAccess.currentEmployeeId()).thenReturn(employeeId);
        endpoint(repository, employeeAccess).perform(get("/api/v1/leaves/me/ledger"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("AUTH_001"));
        verifyNoInteractions(repository);
    }

    private MockMvc endpoint(LeaveLedgerRepository repository, EmployeeAccess employeeAccess) {
        return MockMvcBuilders.standaloneSetup(new MyLeaveLedgerController(new MyLeaveLedgerService(repository, employeeAccess)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
}
