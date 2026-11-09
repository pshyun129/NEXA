package com.ssafy.user_service.support;

import com.ssafy.user_service.global.security.SecurityConstants;
import jakarta.servlet.http.Cookie;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Arrays;

/**
 * Postgres + Redis 컨테이너를 띄우는 통합 테스트 베이스. Docker 가 필요하다.
 * dev 프로필로 db/seed(관리자 admin@local.dev / admin1234!)까지 적용된다.
 * 컨테이너는 JVM 당 한 번만 띄우고 모든 테스트 클래스가 공유한다(싱글턴).
 * @Container 를 쓰면 클래스마다 컨테이너를 내렸다 올리는데, 캐시된 Spring 컨텍스트는 첫 컨테이너의 포트를
 * 기억하고 있어 두 번째 클래스부터 연결이 끊긴다. 그래서 static 초기화로 직접 start 하고 JVM 종료 시 정리한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    /**
     * gateway 를 거친 요청처럼 보낸다: 쿠키는 그대로 두고, accessToken 쿠키 값을 Authorization: Bearer 로 붙인다.
     * (인증 API 인 /auth/** 는 gateway 가 헤더를 지우므로 .cookie(...) 만 쓴다)
     */
    protected static RequestPostProcessor viaGateway(Cookie[] cookies) {
        return request -> {
            request.setCookies(cookies);
            Arrays.stream(cookies)
                    .filter(c -> c.getName().equals(SecurityConstants.ACCESS_COOKIE))
                    .findFirst()
                    .ifPresent(c -> request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + c.getValue()));
            return request;
        };
    }
}
