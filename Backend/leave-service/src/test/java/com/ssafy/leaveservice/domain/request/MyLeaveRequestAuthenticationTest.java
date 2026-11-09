package com.ssafy.leaveservice.domain.request;

import com.ssafy.leaveservice.domain.request.controller.MyLeaveRequestController;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequest;
import com.ssafy.leaveservice.domain.request.repository.LeaveRequestRepository;
import com.ssafy.leaveservice.domain.request.service.MyLeaveRequestService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.auth.TokenEmployeeAccess;
import com.ssafy.leaveservice.global.exception.GlobalExceptionHandler;
import com.ssafy.leaveservice.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MyLeaveRequestAuthenticationTest {

    private final EmployeeAccess tokenAccess = new TokenEmployeeAccess(org.mockito.Mockito.mock(com.ssafy.leaveservice.global.auth.LeaveAdministratorAccess.class));

    @AfterEach
    void clearToken() {
        TestTokens.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/50"})
    void withoutTokenBothEndpointsAreUnauthorized(String endpoint) throws Exception {
        LeaveRequestRepository repository = mock(LeaveRequestRepository.class);
        endpoint(repository, tokenAccess)
                .perform(get("/api/v1/leaves/me/requests" + endpoint).param("employeeId", "84").header("X-Employee-Id", "84"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("AUTH_001"));
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/50"})
    void tokenEmployeeIsUsedDespiteSpoofedIdentity(String endpoint) throws Exception {
        TestTokens.authenticate(1L, "USER");
        LeaveRequestRepository repository = mock(LeaveRequestRepository.class);
        if (!endpoint.isEmpty()) {
            LeaveRequest request = mock(LeaveRequest.class, RETURNS_DEEP_STUBS);
            when(request.getRequestId()).thenReturn(50L);
            when(request.getEmployeeId()).thenReturn(1L);
            when(request.getDates()).thenReturn(List.of());
            when(repository.findDetailByRequestIdAndEmployeeId(50L, 1L)).thenReturn(Optional.of(request));
        }
        MockMvc mvc = endpoint(repository, tokenAccess);
        mvc.perform(get("/api/v1/leaves/me/requests" + endpoint).param("employeeId", "84").header("X-Employee-Id", "84"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        if (endpoint.isEmpty()) {
            verify(repository).findAllWithLeaveTypeByEmployeeId(1L);
        } else {
            verify(repository).findDetailByRequestIdAndEmployeeId(50L, 1L);
        }
        verify(repository, never()).findAllWithLeaveTypeByEmployeeId(84L);
        verify(repository, never()).findDetailByRequestIdAndEmployeeId(anyLong(), eq(84L));
    }

    @ParameterizedTest
    @MethodSource("invalidIdsAndEndpoints")
    void invalidAuthenticatedIdCannotReadEitherEndpoint(Long employeeId, String endpoint) throws Exception {
        EmployeeAccess employeeAccess = mock(EmployeeAccess.class);
        when(employeeAccess.currentEmployeeId()).thenReturn(employeeId);
        LeaveRequestRepository repository = mock(LeaveRequestRepository.class);
        endpoint(repository, employeeAccess).perform(get("/api/v1/leaves/me/requests" + endpoint))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("AUTH_001"));
        verifyNoInteractions(repository);
    }

    private MockMvc endpoint(LeaveRequestRepository repository, EmployeeAccess employeeAccess) {
        return MockMvcBuilders.standaloneSetup(new MyLeaveRequestController(new MyLeaveRequestService(repository, employeeAccess)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    static Stream<Arguments> invalidIdsAndEndpoints() {
        return Stream.<Long>of(null, 0L, -1L)
                .flatMap(employeeId -> Stream.of("", "/50").map(endpoint -> Arguments.of(employeeId, endpoint)));
    }
}
