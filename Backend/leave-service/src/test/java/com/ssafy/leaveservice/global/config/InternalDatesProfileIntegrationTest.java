package com.ssafy.leaveservice.global.config;

import com.ssafy.leaveservice.domain.request.dto.LeaveRequestDatesResponse;
import com.ssafy.leaveservice.domain.request.dto.LeaveRequestDatesResponse.LeaveDateItem;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestPortion;
import com.ssafy.leaveservice.domain.request.service.LeaveRequestDatesService;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "leave.test.database-owner=InternalDatesProfileIntegrationTest",
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
abstract class InternalDatesProfileIntegrationTest {
    private static final String DATES = "/api/v1/leaves/internal/requests/42/dates";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private LeaveRequestDatesService datesService;

    @Test
    void anonymousGetKeepsExistingResponseWithoutAuthorizationHeader() throws Exception {
        LeaveRequestDatesResponse response = new LeaveRequestDatesResponse(
                42L,
                7L,
                "연차",
                true,
                List.of(new LeaveDateItem(LocalDate.of(2026, 11, 9), LeaveRequestPortion.FULL))
        );
        when(datesService.getDates(42L)).thenReturn(response);

        mvc.perform(get(DATES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").isEmpty())
                .andExpect(jsonPath("$.data.requestId").value(42))
                .andExpect(jsonPath("$.data.employeeId").value(7))
                .andExpect(jsonPath("$.data.leaveTypeName").value("연차"))
                .andExpect(jsonPath("$.data.isPaid").value(true))
                .andExpect(jsonPath("$.data.dates[0].leaveDate").value("2026-11-09"))
                .andExpect(jsonPath("$.data.dates[0].portion").value("FULL"))
                .andExpect(jsonPath("$.data.reason").doesNotExist());
    }

    @Test
    void missingRequestStillReturns404() throws Exception {
        when(datesService.getDates(42L)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

        mvc.perform(get(DATES)).andExpect(status().isNotFound());
    }

    @Test
    void corruptedDatesStillReturn409() throws Exception {
        when(datesService.getDates(42L)).thenThrow(new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION));

        mvc.perform(get(DATES)).andExpect(status().isConflict());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc"})
    void invalidRequestIdStillReturns400(String requestId) throws Exception {
        when(datesService.getDates(0L)).thenThrow(new BusinessException(ErrorCode.INVALID_INPUT));
        when(datesService.getDates(-1L)).thenThrow(new BusinessException(ErrorCode.INVALID_INPUT));

        mvc.perform(get("/api/v1/leaves/internal/requests/{requestId}/dates", requestId))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"})
    void otherMethodsKeepInternalAccessDenied(String method) throws Exception {
        HttpMethod httpMethod = HttpMethod.valueOf(method);
        mvc.perform(request(httpMethod, DATES)).andExpect(status().isUnauthorized());
        mvc.perform(request(httpMethod, DATES).with(jwt()))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/leaves/internal/requests/42/other",
            "/api/v1/leaves/internal/requests/42/extra/dates",
            "/api/v1/leaves/internal/other"
    })
    void otherInternalPathsRemainBlocked(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(jwt())).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/leaves/me/balances",
            "/api/v1/leaves/admin/types",
            "/api/v1/leaves/departments/3/requests",
            "/api/v1/leaves/departments/3/balances"
    })
    void unrelatedApisStillRequireAuthentication(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }
}

@ActiveProfiles("local")
class InternalDatesLocalIntegrationTest extends InternalDatesProfileIntegrationTest {
}

@ActiveProfiles("dev")
class InternalDatesDevIntegrationTest extends InternalDatesProfileIntegrationTest {
}

@ActiveProfiles("prod")
class InternalDatesProdIntegrationTest extends InternalDatesProfileIntegrationTest {
}
