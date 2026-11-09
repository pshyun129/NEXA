package com.ssafy.user_service.global.dto;

import com.ssafy.user_service.global.exception.ErrorCode;
import lombok.Getter;

import java.util.Map;

@Getter
public class ApiResult<T> {

    private final boolean success;
    private final ErrorResponse error;
    private final T data;

    private ApiResult(boolean success, ErrorResponse error, T data) {
        this.success = success;
        this.error = error;
        this.data = data;
    }

    public static <T> ApiResult<T> success(T data) {
        return new ApiResult<>(true, null, data);
    }

    public static <T> ApiResult<T> fail(ErrorCode errorCode) {
        return new ApiResult<>(false, ErrorResponse.of(errorCode), null);
    }

    public static <T> ApiResult<T> fail(ErrorCode errorCode, String message) {
        return new ApiResult<>(false, ErrorResponse.of(errorCode, message), null);
    }

    public static <T> ApiResult<T> fail(ErrorCode errorCode, String message, Map<String, Object> details) {
        return new ApiResult<>(false, ErrorResponse.of(errorCode, message, details), null);
    }
}
