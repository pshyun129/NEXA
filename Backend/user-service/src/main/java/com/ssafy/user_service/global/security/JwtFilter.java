package com.ssafy.user_service.global.security;

import com.ssafy.user_service.global.exception.ErrorCode;
import com.ssafy.user_service.global.security.jwt.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authorization: Bearer 헤더의 access 토큰을 검증해 SecurityContext에 인증을 등록한다.
 * (로그인/재발급/로그아웃은 AuthController가 쿠키를 직접 읽으며 permitAll이라 이 필터와 무관하다)
 * 토큰이 없거나 유효하지 않으면 인증을 등록하지 않고 다음 필터로 넘긴다.
 * 보호된 경로라면 이후 AuthorizationFilter가 401을 발생시키고 JwtAuthenticationEntryPoint가
 * 응답을 작성하며, 그때 이 필터가 request attribute에 남긴 ErrorCode를 사용한다.
 * (이 필터는 ExceptionTranslationFilter보다 앞에 있으므로 여기서 예외를 던지면 500이 된다)
 */
@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    public static final String JWT_ERROR_ATTRIBUTE = "jwtError";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;
    private final TokenStore tokenStore;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = resolveToken(request);

        // 토큰이 없는 경우는 오류가 아니라 미인증 상태 → 그대로 통과
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            // 1) 서명/만료 검증 (검증 전에는 Redis 등 외부 조회를 하지 않는다)
            Claims claims = jwtUtil.parseClaims(token);

            // 2) refreshToken을 accessToken 자리에 넣는 것을 차단
            if (!JwtUtil.TYPE_ACCESS.equals(jwtUtil.getType(claims))) {
                reject(request, ErrorCode.TOKEN_INVALID);
            }
            // 3) 로그아웃된 토큰(jti 블랙리스트) 차단
            else if (tokenStore.isBlacklisted(jwtUtil.getTokenId(claims))) {
                reject(request, ErrorCode.TOKEN_BLACKLISTED);
            }
            else {
                Role role = jwtUtil.getRole(claims);
                List<GrantedAuthority> authorities = role == null
                        ? List.of()
                        : List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
                AuthUser principal = new AuthUser(jwtUtil.getUserId(claims), role);
                SecurityContextHolder.getContext()
                        .setAuthentication(new JwtAuthenticationToken(principal, authorities));
            }
        } catch (ExpiredJwtException e) {
            reject(request, ErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            // 위변조, 형식 오류, subject 파싱 실패 등
            reject(request, ErrorCode.TOKEN_INVALID);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Authorization: Bearer 헤더만 읽는다. 브라우저의 accessToken 쿠키는 gateway 가 검증한 뒤 Bearer 로 바꿔 넘기고,
     * 서비스 간 호출(approval-service 등)은 사용자 토큰을 그대로 Bearer 로 보낸다
     */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX) && header.length() > BEARER_PREFIX.length()) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    private void reject(HttpServletRequest request, ErrorCode errorCode) {
        SecurityContextHolder.clearContext();
        request.setAttribute(JWT_ERROR_ATTRIBUTE, errorCode);
    }
}
