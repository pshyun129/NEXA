package com.ssafy.leaveservice.domain.leavetype;

import com.ssafy.leaveservice.domain.leavetype.controller.RequestableLeaveTypeController;
import com.ssafy.leaveservice.domain.leavetype.repository.LeaveTypeRepository;
import com.ssafy.leaveservice.domain.leavetype.service.RequestableLeaveTypeService;
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

class RequestableLeaveTypeAuthenticationTest {

    private final EmployeeAccess tokenAccess = new TokenEmployeeAccess(org.mockito.Mockito.mock(com.ssafy.leaveservice.global.auth.LeaveAdministratorAccess.class));

    @AfterEach
    void clearToken() {
        TestTokens.clear();
    }

    @Test
    void withoutTokenSpoofedEmployeeIsRejectedBeforeAnyQuery() throws Exception {
        EmployeeLeavePolicyAssignmentRepository assignments = mock(EmployeeLeavePolicyAssignmentRepository.class);
        LeaveTypeRepository types = mock(LeaveTypeRepository.class);
        endpoint(assignments, types, tokenAccess)
                .perform(get("/api/v1/leaves/types").param("employeeId", "84").header("X-Employee-Id", "84"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("AUTH_001"));
        verifyNoInteractions(assignments, types);
    }

    @Test
    void usesOnlyTokenEmployee() throws Exception {
        TestTokens.authenticate(1L, "USER");
        EmployeeLeavePolicyAssignmentRepository assignments = mock(EmployeeLeavePolicyAssignmentRepository.class);
        LeaveTypeRepository types = mock(LeaveTypeRepository.class);
        endpoint(assignments, types, tokenAccess)
                .perform(get("/api/v1/leaves/types").param("employeeId", "84").header("X-Employee-Id", "84"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("LEAVE_010"));
        verify(assignments).findApplicablePolicies(eq(1L), any());
        verify(assignments, never()).findApplicablePolicies(eq(84L), any());
        verifyNoInteractions(types);
    }

    private MockMvc endpoint(
            EmployeeLeavePolicyAssignmentRepository assignments,
            LeaveTypeRepository types,
            EmployeeAccess employeeAccess
    ) {
        MyLeavePolicyService policyService = new MyLeavePolicyService(assignments, employeeAccess);
        RequestableLeaveTypeService typeService = new RequestableLeaveTypeService(policyService, types);
        return MockMvcBuilders.standaloneSetup(new RequestableLeaveTypeController(typeService))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
}
