package com.ssafy.migrationservice.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Postgres 컨테이너를 띄우는 통합 테스트 베이스. Docker 가 필요하다.
 * local 프로필로 Eureka·Config 없이 단독 실행한다.
 * 컨테이너는 JVM 당 한 번만 띄우고 모든 테스트 클래스가 공유한다 (user-service와 같은 방식).
 * MockMvc 요청에는 기본으로 관리자 토큰을 붙인다 (권한 자체는 SecurityIntegrationTest에서 확인).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(AbstractIntegrationTest.AdminTokenByDefault.class)
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @TestConfiguration
    static class AdminTokenByDefault {

        @Bean
        MockMvcBuilderCustomizer adminToken() {
            return builder -> builder.defaultRequest(get("/").with(jwt()
                    .jwt(j -> j.subject("1").claim("type", "access").claim("role", "ADMIN"))
                    .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        }
    }
}
