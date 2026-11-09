package com.ssafy.leaveservice.domain.request;

import com.ssafy.leaveservice.domain.request.controller.AdminLeaveRequestController;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequest;
import com.ssafy.leaveservice.domain.request.repository.LeaveRequestRepository;
import com.ssafy.leaveservice.domain.request.service.AdminLeaveRequestService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.auth.TokenEmployeeAccess;
import com.ssafy.leaveservice.global.exception.GlobalExceptionHandler;
import com.ssafy.leaveservice.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminLeaveRequestAuthenticationTest {

    private final com.ssafy.leaveservice.global.auth.LeaveAdministratorAccess administratorAccess =
            mock(com.ssafy.leaveservice.global.auth.LeaveAdministratorAccess.class);
    private final EmployeeAccess tokenAccess = new TokenEmployeeAccess(administratorAccess);

    @AfterEach
    void clearToken() {
        TestTokens.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/50", "/abc"})
    void userTokenIsDeniedEvenWithSpoofedAdminHeadersAndMalformedIds(String endpoint) throws Exception {
        TestTokens.authenticate(1L, "USER");
        LeaveRequestRepository repository = mock(LeaveRequestRepository.class);
        AdminLeaveRequestService service = new AdminLeaveRequestService(repository, tokenAccess);
        MockMvcBuilders.standaloneSetup(new AdminLeaveRequestController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(get("/api/v1/leaves/admin/requests" + endpoint).header("X-Admin", "true").header("X-Employee-Id", "1"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("AUTH_009"));
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/50"})
    void adminTokenIsNotRestrictedToOwnRequests(String endpoint) throws Exception {
        TestTokens.authenticate(1L, "USER");
        when(administratorAccess.isAdministrator(1L)).thenReturn(true);
        LeaveRequestRepository repository = mock(LeaveRequestRepository.class);
        LeaveRequest request = mock(LeaveRequest.class, RETURNS_DEEP_STUBS);
        when(request.getRequestId()).thenReturn(50L);
        when(request.getEmployeeId()).thenReturn(84L);
        when(request.getDates()).thenReturn(List.of());
        when(repository.findAllWithLeaveType()).thenReturn(List.of(request));
        when(repository.findDetailByRequestId(50L)).thenReturn(Optional.of(request));
        AdminLeaveRequestService service = new AdminLeaveRequestService(repository, tokenAccess);
        MockMvcBuilders.standaloneSetup(new AdminLeaveRequestController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(get("/api/v1/leaves/admin/requests" + endpoint))
                .andExpect(status().isOk()).andExpect(jsonPath(endpoint.isEmpty() ? "$.data[0].employeeId" : "$.data.employeeId").value(84));
        if (endpoint.isEmpty()) {
            verify(repository).findAllWithLeaveType();
        } else {
            verify(repository).findDetailByRequestId(50L);
        }
        verify(repository, never()).findAllWithLeaveTypeByEmployeeId(anyLong());
        verify(repository, never()).findDetailByRequestIdAndEmployeeId(anyLong(), anyLong());
    }
}
