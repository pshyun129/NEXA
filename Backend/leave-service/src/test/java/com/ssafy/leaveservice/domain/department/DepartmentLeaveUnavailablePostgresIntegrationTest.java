package com.ssafy.leaveservice.domain.department;

import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import com.ssafy.leaveservice.client.hr.HrClient;
import com.ssafy.leaveservice.client.hr.UnavailableHrClient;
import com.ssafy.leaveservice.domain.account.repository.LeaveAccountRepository;
import com.ssafy.leaveservice.domain.request.repository.LeaveRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
        "leave.test.database-owner=DepartmentLeaveUnavailablePostgresIntegrationTest",
        "spring.cloud.config.enabled=false","eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/","spring.jpa.open-in-view=false"})
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class DepartmentLeaveUnavailablePostgresIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired HrClient hrClient;
    @MockitoBean LeaveAccountRepository accounts;
    @MockitoBean LeaveRequestRepository requests;

    @ParameterizedTest
    @ValueSource(strings={"requests","balances"})
    void defaultConfigurationBlocksBeforeSensitiveLeaveSelect(String endpoint) throws Exception {
        mvc.perform(get("/api/v1/leaves/departments/3/"+endpoint)
                        .with(jwt().jwt(token->token.subject("10").claim("role","ADMIN"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("LEAVE_017"));
        assertThat(hrClient).isInstanceOf(UnavailableHrClient.class);
        verifyNoInteractions(accounts,requests);
    }

    @Test
    void unauthenticatedRequestStillUsesCommon401() throws Exception {
        mvc.perform(get("/api/v1/leaves/departments/3/balances"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("AUTH_001"));
        verifyNoInteractions(accounts,requests);
    }
}
