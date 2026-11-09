package com.ssafy.user_service.global.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ssafy.user_service.global.exception.ErrorCode;

import java.util.Map;

/**
 * 오류 응답 본문.
 * details 는 오류별 추가 정보(선택)다. 예: AUTH_010 → lockedUntil·retryAfterSeconds, AUTH_005 → remainingAttempts.
 * 값이 없으면 JSON 에서 빠지므로 기존 응답 형식은 그대로다
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, Map<String, Object> details) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(errorCode.getCode(), message, null);
    }

    public static ErrorResponse of(ErrorCode errorCode, String message, Map<String, Object> details) {
        return new ErrorResponse(errorCode.getCode(), message, details == null || details.isEmpty() ? null : details);
    }
}
