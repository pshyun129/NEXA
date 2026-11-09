package com.ssafy.user_service.global.security;

import com.ssafy.user_service.global.exception.ErrorCode;
import com.ssafy.user_service.global.security.jwt.JwtKeyProvider;
import com.ssafy.user_service.global.security.jwt.JwtProperties;
import com.ssafy.user_service.global.security.jwt.JwtUtil;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtFilterTest {

    private final TokenStore tokenStore = mock(TokenStore.class);
    private JwtUtil jwtUtil;
    private JwtKeyProvider keys;
    private JwtFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        JwtProperties jwtProps = new JwtProperties("", "", 900_000L, 604_800_000L);
        keys = new JwtKeyProvider(jwtProps);
        keys.init();
        jwtUtil = new JwtUtil(keys, jwtProps);
        filter = new JwtFilter(jwtUtil, tokenStore);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** access 토큰을 Bearer 헤더로 보낸다 (null 이면 헤더 없음) */
    private MockHttpServletRequest run(String accessToken) throws Exception {
        return runWithHeader(accessToken == null ? null : "Bearer " + accessToken);
    }

    private MockHttpServletRequest runWithHeader(String authorization) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/x");
        if (authorization != null) req.addHeader("Authorization", authorization);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).as("다음 필터로 항상 넘어간다").isNotNull();
        return req;
    }

    private static Object errorOf(MockHttpServletRequest req) {
        return req.getAttribute(JwtFilter.JWT_ERROR_ATTRIBUTE);
    }

    @Test
    void 토큰이_없으면_인증없이_통과() throws Exception {
        MockHttpServletRequest req = run(null);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(errorOf(req)).isNull();
    }

    @Test
    void 유효한_access면_AuthUser와_ROLE_권한을_등록() throws Exception {
        run(jwtUtil.generateAccessToken(42L, Role.ADMIN, null, null, null).value());
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isInstanceOf(JwtAuthenticationToken.class);
        assertThat(((AuthUser) auth.getPrincipal()).employeeId()).isEqualTo(42L);
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
    }

    @Test
    void refresh를_access_자리에_넣으면_TOKEN_INVALID() throws Exception {
        MockHttpServletRequest req = run(jwtUtil.generateRefreshToken(42L).value());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(errorOf(req)).isEqualTo(ErrorCode.TOKEN_INVALID);
    }

    @Test
    void 블랙리스트_토큰은_TOKEN_BLACKLISTED() throws Exception {
        var token = jwtUtil.generateAccessToken(42L, Role.USER, null, null, null);
        when(tokenStore.isBlacklisted(token.id())).thenReturn(true);
        MockHttpServletRequest req = run(token.value());
        assertThat(errorOf(req)).isEqualTo(ErrorCode.TOKEN_BLACKLISTED);
    }

    // ===== 헤더 형식 =====

    @Test
    void Bearer_형식이_아닌_헤더는_토큰_없음으로_통과() throws Exception {
        MockHttpServletRequest req = runWithHeader("Basic dXNlcjpwYXNz");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(errorOf(req)).isNull();
    }

    @Test
    void accessToken_쿠키만_있으면_인증하지_않음() throws Exception {
        // 쿠키 → Bearer 변환은 gateway 가 한다
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/x");
        req.setCookies(new Cookie(SecurityConstants.ACCESS_COOKIE, jwtUtil.generateAccessToken(1L, Role.USER, null, null, null).value()));
        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(errorOf(req)).isNull();
    }

    @Test
    void 만료_토큰은_TOKEN_EXPIRED_위변조는_TOKEN_INVALID() throws Exception {
        JwtUtil expiredIssuer = new JwtUtil(keys, new JwtProperties("", "", -1_000L, 1L));
        assertThat(errorOf(run(expiredIssuer.generateAccessToken(1L, Role.USER, null, null, null).value()))).isEqualTo(ErrorCode.TOKEN_EXPIRED);
        assertThat(errorOf(run("garbage.token.value"))).isEqualTo(ErrorCode.TOKEN_INVALID);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
