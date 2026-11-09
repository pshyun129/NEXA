package com.ssafy.leaveservice.domain.grant;

import com.ssafy.leaveservice.domain.grant.controller.MyLeaveGrantController;
import com.ssafy.leaveservice.domain.grant.repository.LeaveGrantRepository;
import com.ssafy.leaveservice.domain.grant.service.MyLeaveGrantService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.auth.TokenEmployeeAccess;
import com.ssafy.leaveservice.global.exception.GlobalExceptionHandler;
import com.ssafy.leaveservice.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MyLeaveGrantAuthenticationTest {

    private final EmployeeAccess tokenAccess = new TokenEmployeeAccess(org.mockito.Mockito.mock(com.ssafy.leaveservice.global.auth.LeaveAdministratorAccess.class));

    @AfterEach
    void clearToken() {
        TestTokens.clear();
    }

    @Test
    void withoutTokenSpoofedHeadersAreUnauthorized() throws Exception {
        LeaveGrantRepository repository = mock(LeaveGrantRepository.class);
        endpoint(repository, tokenAccess)
                .perform(get("/api/v1/leaves/me/grants").param("employeeId", "84").header("X-Employee-Id", "84"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("AUTH_001"));
        verifyNoInteractions(repository);
    }

    @Test
    void tokenEmployeeIsUsedAndReturns200ForNoRecords() throws Exception {
        TestTokens.authenticate(1L, "USER");
        LeaveGrantRepository repository = mock(LeaveGrantRepository.class);
        endpoint(repository, tokenAccess)
                .perform(get("/api/v1/leaves/me/grants").param("employeeId", "84").header("X-Employee-Id", "84"))
                .andExpect(status().isOk()).andExpect(content().json(
                        "{\"success\":true,\"error\":null,\"data\":[]}", JsonCompareMode.STRICT));
        verify(repository).findAllWithAccountAndBalanceTypeByEmployeeId(1L);
        verify(repository, never()).findAllWithAccountAndBalanceTypeByEmployeeId(84L);
    }

    private MockMvc endpoint(LeaveGrantRepository repository, EmployeeAccess employeeAccess) {
        return MockMvcBuilders.standaloneSetup(new MyLeaveGrantController(new MyLeaveGrantService(repository, employeeAccess)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
}
