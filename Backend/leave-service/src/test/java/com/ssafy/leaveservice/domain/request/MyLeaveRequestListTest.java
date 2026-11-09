package com.ssafy.leaveservice.domain.request;

import com.ssafy.leaveservice.domain.leavetype.entity.LeaveType;
import com.ssafy.leaveservice.domain.request.controller.MyLeaveRequestController;
import com.ssafy.leaveservice.domain.request.dto.MyLeaveRequestListResponse;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequest;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestStatus;
import com.ssafy.leaveservice.domain.request.repository.LeaveRequestRepository;
import com.ssafy.leaveservice.domain.request.service.MyLeaveRequestService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import com.ssafy.leaveservice.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MyLeaveRequestListTest {

    private final LeaveRequestRepository repository = mock(LeaveRequestRepository.class);
    private final EmployeeAccess employeeAccess = mock(EmployeeAccess.class);
    private final MyLeaveRequestService service = new MyLeaveRequestService(repository, employeeAccess);

    @Test
    void mapsStoredStatusAndHeaderQuantitiesWithoutCurrentPolicyOrAdminCheck() {
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        LeaveType leaveType = mock(LeaveType.class);
        when(leaveType.getLeaveTypeId()).thenReturn(1L);
        when(leaveType.getName()).thenReturn("연차");
        LeaveRequest request = mock(LeaveRequest.class);
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        when(request.getRequestId()).thenReturn(50L);
        when(request.getPolicyVersionId()).thenReturn(11L);
        when(request.getLeaveType()).thenReturn(leaveType);
        when(request.getStatus()).thenReturn(LeaveRequestStatus.SUBMITTED);
        when(request.getTotalMinutes()).thenReturn(999);
        when(request.getTotalDeductMinutes()).thenReturn(333);
        when(request.getCreatedAt()).thenReturn(createdAt);
        when(request.getUpdatedAt()).thenReturn(createdAt);
        when(repository.findAllWithLeaveTypeByEmployeeId(42L)).thenReturn(List.of(request));

        assertThat(service.getMyRequests()).containsExactly(new MyLeaveRequestListResponse(
                50L, 11L, 1L, "연차", LeaveRequestStatus.SUBMITTED, 999, 333, null, createdAt, createdAt));
        verify(repository).findAllWithLeaveTypeByEmployeeId(42L);
        verifyNoMoreInteractions(repository);
        verify(employeeAccess, never()).isAdmin(anyLong());
    }

    @Test
    void emptyListIsSuccessfulApiResult() throws Exception {
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        MockMvcBuilders.standaloneSetup(new MyLeaveRequestController(service)).setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(get("/api/v1/leaves/me/requests"))
                .andExpect(status().isOk()).andExpect(content().json("{\"success\":true,\"error\":null,\"data\":[]}"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void invalidAuthenticatedEmployeeCannotQuery(Long employeeId) {
        when(employeeAccess.currentEmployeeId()).thenReturn(employeeId);
        assertError(ErrorCode.UNAUTHORIZED);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"UNAUTHORIZED", "AUTHENTICATION_UNAVAILABLE"})
    void authenticationErrorsArePreserved(ErrorCode errorCode) {
        when(employeeAccess.currentEmployeeId()).thenThrow(new BusinessException(errorCode));
        assertError(errorCode);
        verifyNoInteractions(repository);
    }

    private void assertError(ErrorCode errorCode) {
        assertThatThrownBy(service::getMyRequests).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
