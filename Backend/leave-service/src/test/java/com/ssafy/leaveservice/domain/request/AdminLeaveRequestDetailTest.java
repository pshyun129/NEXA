package com.ssafy.leaveservice.domain.request;

import com.ssafy.leaveservice.domain.request.controller.AdminLeaveRequestController;
import com.ssafy.leaveservice.domain.request.dto.MyLeaveRequestDetailResponse;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequest;
import com.ssafy.leaveservice.domain.request.repository.LeaveRequestRepository;
import com.ssafy.leaveservice.domain.request.service.AdminLeaveRequestService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import com.ssafy.leaveservice.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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

class AdminLeaveRequestDetailTest {

    private final LeaveRequestRepository repository = mock(LeaveRequestRepository.class);
    private final EmployeeAccess employeeAccess = mock(EmployeeAccess.class);
    private final AdminLeaveRequestService service = new AdminLeaveRequestService(repository, employeeAccess);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(employeeAccess.currentEmployeeId()).thenReturn(1L);
        when(employeeAccess.isAdmin(1L)).thenReturn(true);
        mvc = MockMvcBuilders.standaloneSetup(new AdminLeaveRequestController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @ParameterizedTest
    @ValueSource(longs = {42, 84})
    void adminMayReadEitherEmployeeUsingExistingDetailConversion(Long employeeId) throws Exception {
        LeaveRequest request = mock(LeaveRequest.class, RETURNS_DEEP_STUBS);
        when(request.getRequestId()).thenReturn(50L);
        when(request.getEmployeeId()).thenReturn(employeeId);
        when(request.getDates()).thenReturn(List.of());
        when(repository.findDetailByRequestId(50L)).thenReturn(Optional.of(request));

        assertThat(service.getRequest("50")).isEqualTo(MyLeaveRequestDetailResponse.from(request));
        mvc.perform(get("/api/v1/leaves/admin/requests/50"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").isEmpty()).andExpect(jsonPath("$.data.employeeId").value(employeeId));
        verify(repository, never()).findDetailByRequestIdAndEmployeeId(anyLong(), anyLong());
    }

    @Test
    void absentRequestUsesExisting404() throws Exception {
        mvc.perform(get("/api/v1/leaves/admin/requests/999"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("COMMON_003"))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "0", "-1", "abc", "1.5", "9223372036854775808", " 50 "})
    void invalidIdsRejectedAfterAdminCheckWithoutDatabaseRead(String requestId) {
        assertThatThrownBy(() -> service.getRequest(requestId)).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        verify(employeeAccess).isAdmin(1L);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"50", "0", "-1", "abc", "9223372036854775808"})
    void nonAdminDeniedBeforeIdValidationEvenOnDirectServiceCall(String requestId) throws Exception {
        when(employeeAccess.isAdmin(1L)).thenReturn(false);
        assertThatThrownBy(() -> service.getRequest(requestId)).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
        mvc.perform(get("/api/v1/leaves/admin/requests/" + requestId).header("X-Admin", "true"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("AUTH_009"));
        verifyNoInteractions(repository);
    }
}
