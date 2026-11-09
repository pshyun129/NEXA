package com.ssafy.nexa.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/** 401 응답. 토큰 없음 AUTH_001, 만료 AUTH_002, 그 밖의 잘못된 토큰 AUTH_003 */
public class NexaAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        resolve(authException).write(response);
    }

    private AuthErrorWriter resolve(AuthenticationException e) {
        if (!(e instanceof OAuth2AuthenticationException)) {
            return AuthErrorWriter.UNAUTHORIZED;
        }
        String message = e.getMessage();
        return message != null && message.contains("expired")
                ? AuthErrorWriter.TOKEN_EXPIRED
                : AuthErrorWriter.TOKEN_INVALID;
    }
}
