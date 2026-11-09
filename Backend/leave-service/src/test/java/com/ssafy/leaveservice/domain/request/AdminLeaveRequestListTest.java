package com.ssafy.leaveservice.domain.request;

import com.ssafy.leaveservice.domain.request.controller.AdminLeaveRequestController;
import com.ssafy.leaveservice.domain.request.dto.AdminLeaveRequestListResponse;
import com.ssafy.leaveservice.domain.request.dto.MyLeaveRequestListResponse;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequest;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestStatus;
import com.ssafy.leaveservice.domain.request.repository.LeaveRequestRepository;
import com.ssafy.leaveservice.domain.request.service.AdminLeaveRequestService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import com.ssafy.leaveservice.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminLeaveRequestListTest {

    private final LeaveRequestRepository repository = mock(LeaveRequestRepository.class);
    private final EmployeeAccess employeeAccess = mock(EmployeeAccess.class);
    private final AdminLeaveRequestService service = new AdminLeaveRequestService(repository, employeeAccess);

    @BeforeEach
    void setUp() {
        when(employeeAccess.currentEmployeeId()).thenReturn(1L);
        when(employeeAccess.isAdmin(1L)).thenReturn(true);
    }

    @ParameterizedTest
    @EnumSource(LeaveRequestStatus.class)
    void includesStoredEmployeeAndReusesAllUserFields(LeaveRequestStatus status) {
        LeaveRequest request = mock(LeaveRequest.class, RETURNS_DEEP_STUBS);
        when(request.getRequestId()).thenReturn(50L);
        when(request.getEmployeeId()).thenReturn(84L);
        when(request.getPolicyVersionId()).thenReturn(11L);
        when(request.getLeaveType().getLeaveTypeId()).thenReturn(1L);
        when(request.getLeaveType().getName()).thenReturn("비활성 연차");
        when(request.getStatus()).thenReturn(status);
        when(request.getTotalMinutes()).thenReturn(999);
        when(request.getTotalDeductMinutes()).thenReturn(333);
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        when(request.getCreatedAt()).thenReturn(createdAt);
        when(request.getUpdatedAt()).thenReturn(createdAt);
        when(repository.findAllWithLeaveType()).thenReturn(List.of(request));

        MyLeaveRequestListResponse userResponse = MyLeaveRequestListResponse.from(request);
        assertThat(service.getAllRequests()).containsExactly(new AdminLeaveRequestListResponse(
                userResponse.requestId(), 84L, userResponse.policyVersionId(), userResponse.leaveTypeId(),
                userResponse.leaveTypeName(), userResponse.status(), userResponse.totalMinutes(),
                userResponse.totalDeductMinutes(), userResponse.submittedAt(), userResponse.createdAt(), userResponse.updatedAt()));
        verify(repository).findAllWithLeaveType();
        verifyNoMoreInteractions(repository);
        verify(request, never()).getDates();
    }

    @Test
    void emptyListReturnsSuccessfulApiResult() throws Exception {
        MockMvcBuilders.standaloneSetup(new AdminLeaveRequestController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(get("/api/v1/leaves/admin/requests"))
                .andExpect(status().isOk()).andExpect(content().json("{\"success\":true,\"error\":null,\"data\":[]}"));
    }

    @Test
    void serviceDirectCallRequiresAdminBeforeAnyQuery() {
        when(employeeAccess.isAdmin(1L)).thenReturn(false);
        assertThatThrownBy(service::getAllRequests).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
        verifyNoInteractions(repository);
    }

    @Test
    void spoofedAdminHeadersDoNotAllowAccess() throws Exception {
        when(employeeAccess.isAdmin(1L)).thenReturn(false);
        MockMvcBuilders.standaloneSetup(new AdminLeaveRequestController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(get("/api/v1/leaves/admin/requests").header("X-Admin", "true").header("X-Employee-Id", "84"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("AUTH_009"));
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"UNAUTHORIZED", "AUTHENTICATION_UNAVAILABLE"})
    void preservesAuthenticationFailures(ErrorCode errorCode) {
        when(employeeAccess.currentEmployeeId()).thenThrow(new BusinessException(errorCode));
        assertThatThrownBy(service::getAllRequests).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
        verifyNoInteractions(repository);
    }
}
