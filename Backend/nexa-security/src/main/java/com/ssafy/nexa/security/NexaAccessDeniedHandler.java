package com.ssafy.nexa.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/** 403 응답. 토큰은 맞는데 권한이 부족할 때 AUTH_009 */
public class NexaAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        AuthErrorWriter.ACCESS_DENIED.write(response);
    }
}
