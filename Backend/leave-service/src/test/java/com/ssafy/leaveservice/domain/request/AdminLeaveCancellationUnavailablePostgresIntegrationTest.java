package com.ssafy.leaveservice.domain.request;

import com.ssafy.leaveservice.client.approval.withdrawal.ApprovalWithdrawalClient;
import com.ssafy.leaveservice.client.approval.withdrawal.ApprovalWithdrawalProperties;
import com.ssafy.leaveservice.client.approval.withdrawal.UnavailableApprovalWithdrawalClient;
import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"leave.test.database-owner=AdminLeaveCancellationUnavailablePostgresIntegrationTest",
        "leave.hr.enabled=true","leave.hr.jwt-claims.duty-id=dutyId",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/", "spring.jpa.open-in-view=false"})
@Import(PostgresTestConfiguration.class)
@AutoConfigureMockMvc
@ActiveProfiles("production")
class AdminLeaveCancellationUnavailablePostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApprovalWithdrawalClient client;
    @Autowired ApprovalWithdrawalProperties properties;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.ssafy.leaveservice.client.hr.HrClient hrClient;

    @Test
    void productionDefaultBlocksBeforeRequestLookupOrAnyInsert() throws Exception {
        org.mockito.Mockito.when(hrClient.isAvailable()).thenReturn(true);
        org.mockito.Mockito.when(hrClient.findEmployee(10L)).thenReturn(
                new com.ssafy.leaveservice.client.hr.HrClient.EmployeeInfo(10L,1L,10L,null,null,
                        com.ssafy.leaveservice.client.hr.HrClient.EmploymentStatus.EMPLOYED));
        org.mockito.Mockito.when(hrClient.findDuty(10L)).thenReturn(
                new com.ssafy.leaveservice.client.hr.HrClient.DutyInfo(10L,"휴가관리자"));
        assertThat(properties.enabled()).isFalse();
        assertThat(client).isInstanceOf(UnavailableApprovalWithdrawalClient.class);
        mvc.perform(post("/api/v1/leaves/admin/requests/999/cancel")
                        .with(jwt().jwt(j -> j.subject("10").claim("role", "ADMIN").claim("type", "access").claim("dutyId",10L))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType("application/json").content("{\"reason\":\"관리자 취소\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("LEAVE_020"));
        assertThat(jdbc.queryForObject("select count(*) from leave_request", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from leave_ledger", Integer.class)).isZero();
    }

    @Test
    void productionDoesNotBypassJwtWhenIntegrationIsDisabled() throws Exception {
        mvc.perform(post("/api/v1/leaves/admin/requests/999/cancel").with(anonymous())
                        .contentType("application/json").content("{\"reason\":\"관리자 취소\"}"))
                .andExpect(status().isUnauthorized());
    }
}
