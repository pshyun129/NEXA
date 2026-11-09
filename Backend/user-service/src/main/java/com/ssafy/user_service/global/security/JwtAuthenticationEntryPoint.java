package com.ssafy.user_service.global.security;

import com.ssafy.user_service.global.dto.ApiResult;
import com.ssafy.user_service.global.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/** 인증 없음/실패 → 401. JwtFilter가 남긴 구체적 사유(만료/위변조/블랙리스트)가 있으면 그 코드를 쓴다. */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        Object attr = request.getAttribute(JwtFilter.JWT_ERROR_ATTRIBUTE);
        ErrorCode errorCode = attr instanceof ErrorCode code ? code : ErrorCode.UNAUTHORIZED;

        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setStatus(errorCode.getStatus().value());
        response.getWriter().write(objectMapper.writeValueAsString(ApiResult.fail(errorCode)));
    }
}
