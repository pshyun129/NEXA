package com.ssafy.leaveservice.domain.policy;

import com.ssafy.leaveservice.domain.policy.controller.MyLeavePolicyController;
import com.ssafy.leaveservice.domain.policy.repository.EmployeeLeavePolicyAssignmentRepository;
import com.ssafy.leaveservice.domain.policy.service.MyLeavePolicyService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.auth.TokenEmployeeAccess;
import com.ssafy.leaveservice.global.exception.GlobalExceptionHandler;
import com.ssafy.leaveservice.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MyLeavePolicyAuthenticationTest {

    private final EmployeeAccess tokenAccess = new TokenEmployeeAccess(org.mockito.Mockito.mock(com.ssafy.leaveservice.global.auth.LeaveAdministratorAccess.class));

    @AfterEach
    void clearToken() {
        TestTokens.clear();
    }

    @Test
    void withoutTokenEndpointIsBlockedEvenWithSpoofedEmployeeHeader() throws Exception {
        EmployeeLeavePolicyAssignmentRepository repository = mock(EmployeeLeavePolicyAssignmentRepository.class);
        endpoint(repository, tokenAccess)
                .perform(get("/api/v1/leaves/me/policy").header("X-Employee-Id", "42").param("employeeId", "42"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_001"));
        verifyNoInteractions(repository);
    }

    @Test
    void tokenEmployeeIdentityIsKeptDespiteClientParameters() throws Exception {
        TestTokens.authenticate(1L, "USER");
        EmployeeLeavePolicyAssignmentRepository repository = mock(EmployeeLeavePolicyAssignmentRepository.class);
        endpoint(repository, tokenAccess).perform(get("/api/v1/leaves/me/policy")
                        .header("X-Employee-Id", "84").param("employeeId", "84"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("LEAVE_010"));
        verify(repository).findApplicablePolicies(eq(1L), any());
        verify(repository, never()).findApplicablePolicies(eq(84L), any());
    }

    private MockMvc endpoint(EmployeeLeavePolicyAssignmentRepository repository, EmployeeAccess employeeAccess) {
        MyLeavePolicyService service = new MyLeavePolicyService(repository, employeeAccess);
        return MockMvcBuilders.standaloneSetup(new MyLeavePolicyController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
}
