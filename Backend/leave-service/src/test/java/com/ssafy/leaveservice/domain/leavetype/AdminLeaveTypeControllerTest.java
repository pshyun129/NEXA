package com.ssafy.leaveservice.domain.leavetype;

import com.ssafy.leaveservice.domain.leavetype.controller.AdminLeaveTypeController;
import com.ssafy.leaveservice.domain.leavetype.dto.LeaveTypeResponse;
import com.ssafy.leaveservice.domain.leavetype.entity.BalanceMode;
import com.ssafy.leaveservice.domain.leavetype.entity.RequestUnit;
import com.ssafy.leaveservice.domain.leavetype.service.LeaveTypeService;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import com.ssafy.leaveservice.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminLeaveTypeControllerTest {
    private final LeaveTypeService service = mock(LeaveTypeService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminLeaveTypeController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    private static final String URL = "/api/v1/leaves/admin/types";

    @Test
    void returnsTypesInApiResult() throws Exception {
        when(service.getAllTypes()).thenReturn(List.of(new LeaveTypeResponse(
                1L, "연차", true, BalanceMode.ACCOUNT, RequestUnit.DAY, null,
                new LeaveTypeResponse.BalanceTypeResponse(2L, "연차", false), true)));
        mvc.perform(get(URL)).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].leaveTypeId").value(1))
                .andExpect(jsonPath("$.data[0].name").value("연차"))
                .andExpect(jsonPath("$.data[0].paid").value(true))
                .andExpect(jsonPath("$.data[0].isActive").value(true))
                .andExpect(jsonPath("$.data[0].balanceMode").value("ACCOUNT"))
                .andExpect(jsonPath("$.data[0].requestUnit").value("DAY"))
                .andExpect(jsonPath("$.data[0].balanceType.active").value(false));
    }

    @Test
    void returns200AndEmptyArray() throws Exception {
        when(service.getAllTypes()).thenReturn(List.of());

        mvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"success": true, "error": null, "data": []}
                        """));
    }

    @Test
    void returns403WhenAdminCheckFails() throws Exception {
        when(service.getAllTypes()).thenThrow(new BusinessException(ErrorCode.ACCESS_DENIED));

        mvc.perform(get(URL))
                .andExpect(status().isForbidden())
                .andExpect(content().json("""
                        {
                          "success": false,
                          "error": {"code": "AUTH_009", "message": "접근 권한이 없습니다."},
                          "data": null
                        }
                        """));
    }

    @Test
    void returns503WhenRealAuthenticationIsNotConfigured() throws Exception {
        when(service.getAllTypes()).thenThrow(new BusinessException(ErrorCode.AUTHENTICATION_UNAVAILABLE));

        mvc.perform(get(URL))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json("""
                        {
                          "success": false,
                          "error": {"code": "LEAVE_AUTH_001", "message": "직원 인증 연동이 준비되지 않았습니다."},
                          "data": null
                        }
                        """));
    }
}
