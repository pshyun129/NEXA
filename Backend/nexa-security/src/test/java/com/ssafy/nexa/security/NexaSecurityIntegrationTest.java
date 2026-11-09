package com.ssafy.nexa.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제로 서명한 토큰으로 검증 규칙, 권한, 401/403 응답 본문을 확인한다.
 * JWKS 서버 대신 테스트 공개키로 만든 디코더를 쓰고, 검증 규칙은 라이브러리 것을 그대로 쓴다.
 */
@SpringBootTest(properties = "nexa.security.permit-urls=/public")
@AutoConfigureMockMvc
class NexaSecurityIntegrationTest {

    private static final RSAKey KEY = generateKey();
    private static final RSAKey OTHER_KEY = generateKey();

    @Autowired
    MockMvc mvc;

    @Test
    void 토큰이_없으면_AUTH_001() throws Exception {
        expectError(mvc.perform(get("/me")), 401, "AUTH_001");
    }

    @Test
    void 열어_둔_경로는_토큰_없이_통과() throws Exception {
        mvc.perform(get("/public")).andExpect(status().isOk());
    }

    @Test
    void 정상_토큰이면_요청한_사원이_들어온다() throws Exception {
        mvc.perform(get("/me").header("Authorization", bearer(token(KEY, "7", "access", "USER", 60))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeId").value(7))
                .andExpect(jsonPath("$.admin").value(false));
    }

    @Test
    void 토큰의_소속_ID가_들어온다() throws Exception {
        String token = token(KEY, "7", "access", "USER", 60, Map.of("deptId", 12, "gradeId", 3L, "dutyId", 5));
        mvc.perform(get("/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deptId").value(12))
                .andExpect(jsonPath("$.gradeId").value(3))
                .andExpect(jsonPath("$.dutyId").value(5));
    }

    @Test
    void 소속_claim이_없으면_null() throws Exception {
        mvc.perform(get("/me").header("Authorization", bearer(token(KEY, "7", "access", "USER", 60))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deptId").doesNotExist())
                .andExpect(jsonPath("$.gradeId").doesNotExist())
                .andExpect(jsonPath("$.dutyId").doesNotExist());
    }

    @Test
    void 사원_ID만_받을_수도_있다() throws Exception {
        mvc.perform(get("/me/id").header("Authorization", bearer(token(KEY, "7", "access", "USER", 60))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(7));
    }

    @Test
    void 만료된_토큰이면_AUTH_002() throws Exception {
        expectError(mvc.perform(get("/me").header("Authorization", bearer(token(KEY, "7", "access", "USER", -60)))),
                401, "AUTH_002");
    }

    @Test
    void 다른_키로_서명한_토큰이면_AUTH_003() throws Exception {
        expectError(mvc.perform(get("/me").header("Authorization", bearer(token(OTHER_KEY, "7", "access", "USER", 60)))),
                401, "AUTH_003");
    }

    @Test
    void refresh_토큰이면_AUTH_003() throws Exception {
        expectError(mvc.perform(get("/me").header("Authorization", bearer(token(KEY, "7", "refresh", "USER", 60)))),
                401, "AUTH_003");
    }

    @Test
    void 사원_ID가_숫자가_아니면_AUTH_003() throws Exception {
        expectError(mvc.perform(get("/me").header("Authorization", bearer(token(KEY, "abc", "access", "USER", 60)))),
                401, "AUTH_003");
    }

    @Test
    void 일반_사원이_관리자_경로에_오면_AUTH_009() throws Exception {
        expectError(mvc.perform(get("/admin").header("Authorization", bearer(token(KEY, "7", "access", "USER", 60)))),
                403, "AUTH_009");
    }

    @Test
    void 관리자는_관리자_경로_통과() throws Exception {
        mvc.perform(get("/admin").header("Authorization", bearer(token(KEY, "1", "access", "ADMIN", 60))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admin").value(true));
    }

    private static void expectError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String token(RSAKey key, String subject, String type, String role, long expiresInSeconds)
            throws JOSEException {
        return token(key, subject, type, role, expiresInSeconds, Map.of());
    }

    private static String token(RSAKey key, String subject, String type, String role, long expiresInSeconds,
                                Map<String, Object> extraClaims) throws JOSEException {
        Instant now = Instant.now();
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("type", type)
                .claim("role", role)
                .issueTime(Date.from(now.minusSeconds(120)))
                .expirationTime(Date.from(now.plusSeconds(expiresInSeconds)));
        extraClaims.forEach(builder::claim);
        JWTClaimsSet claims = builder.build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        JwtDecoder jwtDecoder() throws JOSEException {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(NexaJwtValidators.accessToken());
            return decoder;
        }

        @Bean
        SecurityFilterChain filterChain(HttpSecurity http, NexaSecurityConfigurer nexa) throws Exception {
            return nexa.apply(http)
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers("/admin").hasRole("ADMIN")
                            .anyRequest().authenticated())
                    .build();
        }

        @RestController
        static class TestController {

            @GetMapping("/public")
            String open() {
                return "ok";
            }

            @GetMapping({"/me", "/admin"})
            AuthUserView me(@CurrentUser AuthUser user) {
                return new AuthUserView(user.employeeId(), user.isAdmin(),
                        user.deptId(), user.gradeId(), user.dutyId());
            }

            @GetMapping("/me/id")
            Long myId(@CurrentEmployeeId Long employeeId) {
                return employeeId;
            }
        }

        record AuthUserView(Long employeeId, boolean admin, Long deptId, Long gradeId, Long dutyId) {
        }
    }
}
