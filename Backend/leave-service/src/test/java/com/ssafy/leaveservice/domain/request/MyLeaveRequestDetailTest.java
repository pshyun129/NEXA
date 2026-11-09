package com.ssafy.leaveservice.domain.request;

import com.ssafy.leaveservice.domain.request.controller.MyLeaveRequestController;
import com.ssafy.leaveservice.domain.request.dto.MyLeaveRequestDetailResponse;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequest;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestDate;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestPortion;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestStatus;
import com.ssafy.leaveservice.domain.request.repository.LeaveRequestRepository;
import com.ssafy.leaveservice.domain.request.service.MyLeaveRequestService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import com.ssafy.leaveservice.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MyLeaveRequestDetailTest {

    private final LeaveRequestRepository repository = mock(LeaveRequestRepository.class);
    private final EmployeeAccess employeeAccess = mock(EmployeeAccess.class);
    private final MyLeaveRequestService service = new MyLeaveRequestService(repository, employeeAccess);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        mvc = MockMvcBuilders.standaloneSetup(new MyLeaveRequestController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void queriesRequestAndOwnerTogetherAndMapsStoredHeaderAndDates() {
        LeaveRequest request = mock(LeaveRequest.class, RETURNS_DEEP_STUBS);
        when(request.getRequestId()).thenReturn(50L);
        when(request.getEmployeeId()).thenReturn(42L);
        when(request.getPolicyVersionId()).thenReturn(11L);
        when(request.getLeaveType().getLeaveTypeId()).thenReturn(1L);
        when(request.getLeaveType().getName()).thenReturn("현재 연차명");
        when(request.getApprovalDocId()).thenReturn(90050L);
        when(request.getStatus()).thenReturn(LeaveRequestStatus.CANCELLED);
        when(request.getReason()).thenReturn("  개인 사유  ");
        when(request.getTotalMinutes()).thenReturn(999);
        LeaveRequestDate requestDate = mock(LeaveRequestDate.class);
        when(requestDate.getRequestDateId()).thenReturn(501L);
        when(requestDate.getPortion()).thenReturn(LeaveRequestPortion.FULL);
        when(requestDate.getRequestedMinutes()).thenReturn(480);
        when(requestDate.getDeductMinutes()).thenReturn(240);
        when(requestDate.getScheduledWorkMinutes()).thenReturn(null);
        when(request.getDates()).thenReturn(List.of(requestDate));
        when(repository.findDetailByRequestIdAndEmployeeId(50L, 42L)).thenReturn(Optional.of(request));

        MyLeaveRequestDetailResponse response = service.getMyRequest(50L);

        assertThat(response.requestId()).isEqualTo(50);
        assertThat(response.policyVersionId()).isEqualTo(11);
        assertThat(response.approvalDocId()).isEqualTo(90050);
        assertThat(response.totalMinutes()).isEqualTo(999);
        assertThat(response.reason()).isEqualTo("  개인 사유  ");
        assertThat(response.dates()).singleElement().satisfies(date -> {
            assertThat(date.requestDateId()).isEqualTo(501);
            assertThat(date.requestedMinutes()).isEqualTo(480);
            assertThat(date.deductMinutes()).isEqualTo(240);
            assertThat(date.scheduledWorkMinutes()).isNull();
        });
        verify(repository).findDetailByRequestIdAndEmployeeId(50L, 42L);
        verifyNoMoreInteractions(repository);
        verify(employeeAccess, never()).isAdmin(anyLong());
    }

    @Test
    void inaccessibleAndMissingRequestsUseSameExisting404() throws Exception {
        mvc.perform(get("/api/v1/leaves/me/requests/99")).andExpect(status().isNotFound())
                .andExpect(content().json("{\"success\":false,\"error\":{\"code\":\"COMMON_003\",\"message\":\"요청한 리소스를 찾을 수 없습니다.\"},\"data\":null}"));
        verify(repository).findDetailByRequestIdAndEmployeeId(99L, 42L);
        verifyNoMoreInteractions(repository);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void invalidRequestIdIsRejectedBeforeQuery(Long requestId) {
        assertError(() -> service.getMyRequest(requestId), ErrorCode.INVALID_INPUT);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "9223372036854775808", "0", "-1"})
    void invalidPathReturns400(String requestId) throws Exception {
        mvc.perform(get("/api/v1/leaves/me/requests/" + requestId)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void invalidAuthenticatedIdIsRejectedBeforeTargetValidation(Long employeeId) {
        when(employeeAccess.currentEmployeeId()).thenReturn(employeeId);
        assertError(() -> service.getMyRequest(0L), ErrorCode.UNAUTHORIZED);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"UNAUTHORIZED", "AUTHENTICATION_UNAVAILABLE"})
    void preservesAuthenticationFailure(ErrorCode errorCode) {
        when(employeeAccess.currentEmployeeId()).thenThrow(new BusinessException(errorCode));
        assertError(() -> service.getMyRequest(50L), errorCode);
        verifyNoInteractions(repository);
    }

    private void assertError(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
