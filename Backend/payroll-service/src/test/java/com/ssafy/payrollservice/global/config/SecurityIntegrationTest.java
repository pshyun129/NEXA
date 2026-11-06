package com.ssafy.payrollservice.global.config;

import com.ssafy.payrollservice.domain.payroll.dto.PayslipPdf;
import com.ssafy.payrollservice.domain.payroll.service.AdminPayslipService;
import com.ssafy.payrollservice.domain.payroll.service.PayslipService;
import com.ssafy.payrollservice.global.exception.BusinessException;
import com.ssafy.payrollservice.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * nexa-security 적용 확인: 토큰 없으면 401, 관리자 경로 권한, 토큰의 sub 가 사원 ID 로 들어가는지.
 * 인증은 jwt() 로 흉내 내므로 JWKS 서버가 필요 없다. 서비스는 mock 으로 두고 보안 설정만 본다.
 */
@SpringBootTest(properties = {"spring.config.location=classpath:/payroll-ci.yml"})
@AutoConfigureMockMvc
@Testcontainers
class SecurityIntegrationTest {

    private static final String CONTRACT = "/api/v1/pay/me/contract";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PayslipService payslipService;

    @MockitoBean
    AdminPayslipService adminPayslipService;

    @Test
    void 토큰이_없으면_401_AUTH_001() throws Exception {
        mvc.perform(get(CONTRACT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_001"));
    }

    @Test
    void X_User_Id_헤더만_보내면_401() throws Exception {
        mvc.perform(get(CONTRACT).header("X-User-Id", "7"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_001"));
    }

    @Test
    void 토큰의_sub가_사원_ID로_들어감() throws Exception {
        mvc.perform(get("/api/v1/pay/me/payslips?year=2026").with(user(7)))
                .andExpect(status().isOk());

        verify(payslipService).getMyPayslips(7L, 2026);
    }

    @Test
    void 명세서_PDF는_파일로_내려감() throws Exception {
        given(payslipService.getMyPayslipPdf(7L, 1L))
                .willReturn(new PayslipPdf("payslip-2026-10.pdf", "%PDF-test".getBytes(StandardCharsets.US_ASCII)));

        mvc.perform(get("/api/v1/pay/me/payslips/1/pdf").with(user(7)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"payslip-2026-10.pdf\""))
                .andExpect(content().bytes("%PDF-test".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void 다른_사람_명세서_PDF는_404_PAY_005() throws Exception {
        given(payslipService.getMyPayslipPdf(7L, 2L))
                .willThrow(new BusinessException(ErrorCode.PAYSLIP_NOT_FOUND));

        mvc.perform(get("/api/v1/pay/me/payslips/2/pdf").with(user(7)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PAY_005"));
    }

    @Test
    void 일반_사원은_급여_생성_403_AUTH_009() throws Exception {
        mvc.perform(generate().with(user(7)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_009"));
    }

    @Test
    void 토큰의_gradeId가_급여_정보_조회에_들어감() throws Exception {
        mvc.perform(get(CONTRACT).with(user(7, 3)))
                .andExpect(status().isOk());

        verify(payslipService).getMyContract(3L);
    }

    @Test
    void 관리자는_급여_생성_가능() throws Exception {
        mvc.perform(generate().with(admin(1)))
                .andExpect(status().isCreated());
    }

    @Test
    void 헬스체크는_토큰_없이_열림() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder generate() {
        return post("/api/v1/pay/admin/payslips/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"empId":7,"month":"2026-10"}""");
    }

    private static RequestPostProcessor user(long empId) {
        return jwt().jwt(j -> j.subject(String.valueOf(empId)).claim("type", "access").claim("role", "USER"))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static RequestPostProcessor admin(long empId) {
        return jwt().jwt(j -> j.subject(String.valueOf(empId)).claim("type", "access").claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static RequestPostProcessor user(long empId, long gradeId) {
        return jwt().jwt(j -> j.subject(String.valueOf(empId)).claim("type", "access").claim("role", "USER")
                        .claim("gradeId", gradeId))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
}
