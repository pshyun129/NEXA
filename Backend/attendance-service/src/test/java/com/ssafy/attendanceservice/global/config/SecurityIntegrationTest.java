package com.ssafy.attendanceservice.global.config;

import com.ssafy.attendanceservice.domain.attendance.service.AdminAttendanceService;
import com.ssafy.attendanceservice.domain.attendance.service.AdminAttendanceStatusService;
import com.ssafy.attendanceservice.domain.attendance.service.AttendanceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * nexa-security 적용 확인: 토큰 없으면 401, 관리자 경로 권한, 토큰의 sub 가 사원 ID 로 들어가는지.
 * 인증은 jwt() 로 흉내 내므로 JWKS 서버가 필요 없다. 서비스는 mock 으로 두고 보안 설정만 본다.
 */
@SpringBootTest(properties = {"spring.config.location=classpath:/attendance-ci.yml"})
@AutoConfigureMockMvc
@Testcontainers
class SecurityIntegrationTest {

    private static final String TODAY = "/api/v1/attn/today";
    private static final String ADMIN_DAILY = "/api/v1/attn/admin/employees/7/daily/2026-10-30";
    private static final String ADMIN_LOG = "/api/v1/attn/admin/logs/1";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AttendanceService attendanceService;

    @MockitoBean
    AdminAttendanceService adminAttendanceService;

    @MockitoBean
    AdminAttendanceStatusService adminAttendanceStatusService;

    @Test
    void 근태_현황은_일반_사원_토큰도_통과하고_범위는_서비스가_판단() throws Exception {
        mvc.perform(get("/api/v1/attn/admin/daily").param("date", "2026-10-30").with(user(7)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/attn/admin/period").param("from", "2026-10-01").param("to", "2026-10-31")
                        .with(user(7)))
                .andExpect(status().isOk());

        verify(adminAttendanceStatusService).getDaily(any(), eq(LocalDate.of(2026, 10, 30)), isNull(), isNull());
    }

    @Test
    void 토큰이_없으면_401_AUTH_001() throws Exception {
        mvc.perform(get(TODAY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_001"));
    }

    @Test
    void X_User_Id_헤더만_보내면_401() throws Exception {
        mvc.perform(get(TODAY).header("X-User-Id", "7"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_001"));
    }

    @Test
    void 토큰의_sub가_사원_ID로_들어감() throws Exception {
        mvc.perform(get(TODAY).with(user(7)))
                .andExpect(status().isOk());

        verify(attendanceService).getToday(7L);
    }

    @Test
    void 일반_사원은_근무_기록_삭제_403_AUTH_009() throws Exception {
        mvc.perform(delete(ADMIN_LOG).with(user(7)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_009"));
    }

    @Test
    void 관리자는_근무_기록_삭제_가능() throws Exception {
        mvc.perform(delete(ADMIN_LOG).with(admin(1)))
                .andExpect(status().isOk());
    }

    @Test
    void 직원_타임라인_조회는_일반_사원_토큰도_통과하고_범위는_서비스가_판단() throws Exception {
        mvc.perform(get(ADMIN_DAILY).with(user(8)))
                .andExpect(status().isOk());

        verify(attendanceService).getDaily(any(), eq(7L), eq(LocalDate.of(2026, 10, 30)));
    }

    @Test
    void 헬스체크는_토큰_없이_열림() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    private static RequestPostProcessor user(long empId) {
        return jwt().jwt(j -> j.subject(String.valueOf(empId)).claim("type", "access").claim("role", "USER"))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static RequestPostProcessor admin(long empId) {
        return jwt().jwt(j -> j.subject(String.valueOf(empId)).claim("type", "access").claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
