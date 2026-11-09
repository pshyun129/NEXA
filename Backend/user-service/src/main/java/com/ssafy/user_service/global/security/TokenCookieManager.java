package com.ssafy.user_service.global.security;

import com.ssafy.user_service.global.config.AppProperties;
import com.ssafy.user_service.global.security.jwt.JwtProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;

/** access/refresh 쿠키의 읽기·발급·삭제를 한 곳에서 담당한다 (컨트롤러와 필터가 공용). */
@Component
@RequiredArgsConstructor
public class TokenCookieManager {

    private final AppProperties appProperties;
    private final JwtProperties jwtProperties;

    public String readAccessToken(HttpServletRequest request) {
        return read(request, SecurityConstants.ACCESS_COOKIE);
    }

    public String readRefreshToken(HttpServletRequest request) {
        return read(request, SecurityConstants.REFRESH_COOKIE);
    }

    public void setTokenCookies(HttpServletResponse response, String accessToken, String refreshToken) {
        response.addHeader(HttpHeaders.SET_COOKIE, accessCookie(accessToken,
                Duration.ofMillis(jwtProperties.accessExpiration())).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie(refreshToken,
                Duration.ofMillis(jwtProperties.refreshExpiration())).toString());
    }

    /** 발급 때와 같은 name/path 로 Max-Age=0 을 내려야 브라우저가 지움 */
    public void clearTokenCookies(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, accessCookie("", Duration.ZERO).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString());
    }

    private ResponseCookie accessCookie(String value, Duration maxAge) {
        return build(SecurityConstants.ACCESS_COOKIE, value, "/", maxAge);
    }

    // refreshToken은 path를 auth 하위로 제한해 reissue/logout 요청에만 실리게 한다
    private ResponseCookie refreshCookie(String value, Duration maxAge) {
        return build(SecurityConstants.REFRESH_COOKIE, value, SecurityConstants.AUTH_BASE_PATH, maxAge);
    }

    private ResponseCookie build(String name, String value, String path, Duration maxAge) {
        AppProperties.Cookie cookie = appProperties.cookie();
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(cookie.secure())
                .path(path)
                .maxAge(maxAge)
                .sameSite(cookie.sameSite())
                .build();
    }

    private static String read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        return Arrays.stream(cookies)
                .filter(c -> name.equals(c.getName()))
                .findFirst()
                .map(Cookie::getValue)
                .orElse(null);
    }
}
