package com.ssafy.user_service.global.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/** JWT로 인증된 요청을 나타내는 Authentication. 생성 시점에 이미 검증이 끝났으므로 항상 authenticated=true */
public class JwtAuthenticationToken extends AbstractAuthenticationToken {

    private final AuthUser principal;

    public JwtAuthenticationToken(AuthUser principal, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null; // 토큰 원문은 보관하지 않는다
    }

    @Override
    public AuthUser getPrincipal() {
        return principal;
    }
}
