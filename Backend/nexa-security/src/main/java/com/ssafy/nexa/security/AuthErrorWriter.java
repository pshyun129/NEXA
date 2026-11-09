package com.ssafy.nexa.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;

/**
 * 인증 실패 응답을 서비스 공통 형식({"success":false,"error":{"code","message"},"data":null})으로 쓴다.
 * 코드와 메시지는 각 서비스 ErrorCode 의 AUTH_* 와 같다. 값이 고정이라 JSON 라이브러리 없이 쓴다.
 */
enum AuthErrorWriter {

    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "AUTH_001", "인증이 필요합니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "AUTH_002", "토큰이 만료되었습니다."),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "AUTH_003", "유효하지 않은 토큰입니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "AUTH_009", "접근 권한이 없습니다.");

    private final HttpStatus status;
    private final String body;

    AuthErrorWriter(HttpStatus status, String code, String message) {
        this.status = status;
        this.body = """
                {"success":false,"error":{"code":"%s","message":"%s"},"data":null}""".formatted(code, message);
    }

    void write(HttpServletResponse response) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(body);
    }
}
