package com.ssafy.user_service.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** application.properties 의 app.* 설정 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Cookie cookie, Cors cors, Login login) {

    /**
     * @param secure   https 운영 환경에서는 true. SameSite=None을 쓰려면 필수
     * @param sameSite 프론트와 백엔드가 같은 사이트면 Lax, 다른 사이트면 None (이 경우 CSRF 방어 추가 필요)
     */
    public record Cookie(boolean secure, String sameSite) {
    }

    /** @param allowedOrigins 쿠키 인증(allowCredentials)과 함께 쓰므로 와일드카드 없이 정확한 origin만 */
    public record Cors(List<String> allowedOrigins) {
    }

    /**
     * 로그인 실패 제한.
     * @param maxFailures   failureWindow 안에 이 횟수만큼 실패하면 잠금
     * @param failureWindow 실패 횟수를 세는 시간 창
     * @param lockDuration  잠금 유지 시간
     */
    public record Login(int maxFailures, Duration failureWindow, Duration lockDuration) {
    }
}
