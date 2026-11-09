package com.ssafy.leaveservice.support;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * 컨트롤러·서비스 단위 테스트용 인증. nexa-security 가 검증을 마친 access 토큰과 같은 모양을
 * SecurityContext 에 넣어 TokenEmployeeAccess 가 읽게 한다. 테스트가 끝나면 clear() 로 비운다.
 */
public final class TestTokens {

    private TestTokens() {
    }

    public static void authenticate(long employeeId, String role) {
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "RS256")
                .subject(String.valueOf(employeeId)).claim("type", "access").claim("role", role).claim("dutyId", employeeId).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role))));
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}
