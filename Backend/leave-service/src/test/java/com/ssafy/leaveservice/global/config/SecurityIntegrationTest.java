package com.ssafy.leaveservice.global.config;

import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import com.ssafy.leaveservice.domain.account.repository.LeaveAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * nexa-security 적용 확인: 토큰 없으면 401, 관리자 경로 권한, 토큰의 sub 가 TokenEmployeeAccess 로 들어가는지.
 * 인증은 jwt() 로 흉내 내므로 JWKS 서버가 필요 없다. 저장소는 mock 으로 두고 보안 설정만 본다.
 */
@SpringBootTest(properties = {
        "leave.test.database-owner=SecurityIntegrationTest",
        "leave.hr.enabled=true", "leave.hr.jwt-claims.duty-id=dutyId",
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/",
        "spring.jpa.open-in-view=false"})
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class SecurityIntegrationTest {

    private static final String MY_BALANCES = "/api/v1/leaves/me/balances";
    private static final String ADMIN_BALANCES = "/api/v1/leaves/admin/employees/42/balances";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    LeaveAccountRepository accountRepository;

    @MockitoBean
    com.ssafy.leaveservice.domain.request.service.LeaveRequestDatesService datesService;

    @MockitoBean
    com.ssafy.leaveservice.client.hr.HrClient hrClient;

    @org.junit.jupiter.api.BeforeEach
    void hrAuthorization() {
        org.mockito.Mockito.when(hrClient.isAvailable()).thenReturn(true);
        org.mockito.Mockito.when(hrClient.findEmployee(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            Long id = call.getArgument(0);
            return new com.ssafy.leaveservice.client.hr.HrClient.EmployeeInfo(id,1L,id,null,null,
                    com.ssafy.leaveservice.client.hr.HrClient.EmploymentStatus.EMPLOYED);
        });
        org.mockito.Mockito.when(hrClient.findDuty(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            Long id = call.getArgument(0);
            return new com.ssafy.leaveservice.client.hr.HrClient.DutyInfo(id,id == 1L ? "휴가관리자" : "팀원");
        });
    }

    @Test
    void 토큰이_없으면_401_AUTH_001() throws Exception {
        mvc.perform(get(MY_BALANCES))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_001"));
    }

    @Test
    void X_Employee_Id_헤더만_보내면_401() throws Exception {
        mvc.perform(get(MY_BALANCES).header("X-Employee-Id", "7"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_001"));
    }

    @Test
    void 토큰의_sub가_사원_ID로_들어감() throws Exception {
        mvc.perform(get(MY_BALANCES).with(user(7)))
                .andExpect(status().isOk());

        verify(accountRepository).findBalancesByEmployeeId(eq(7L), any());
    }

    @Test
    void 일반_사원은_관리자_API_403_AUTH_009() throws Exception {
        mvc.perform(get(ADMIN_BALANCES).with(user(7)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_009"));
    }

    @Test
    void 관리자는_관리자_API_호출_가능() throws Exception {
        mvc.perform(get(ADMIN_BALANCES).with(admin(1)))
                .andExpect(status().isOk());

        verify(accountRepository).findBalancesByEmployeeId(eq(42L), any());
    }

    @Test
    void 헬스체크는_토큰_없이_열림() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void internalDatesAllowAnonymousAndCurrentUserRoles() throws Exception {
        String path = "/api/v1/leaves/internal/requests/42/dates";
        org.mockito.Mockito.when(datesService.getDates(42L)).thenReturn(
                new com.ssafy.leaveservice.domain.request.dto.LeaveRequestDatesResponse(
                        42L, 7L, "연차", true, java.util.List.of()));
        mvc.perform(get(path).header("X-Service-Name", "attendance-service"))
                .andExpect(status().isOk());
        mvc.perform(get(path).with(user(7)))
                .andExpect(status().isOk());
        mvc.perform(get(path).with(admin(1)))
                .andExpect(status().isOk());
    }

    private static RequestPostProcessor user(long empId) {
        return jwt().jwt(j -> j.subject(String.valueOf(empId)).claim("type", "access").claim("dutyId", empId).claim("role", "USER"))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static RequestPostProcessor admin(long empId) {
        return jwt().jwt(j -> j.subject(String.valueOf(empId)).claim("type", "access").claim("dutyId", empId).claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
