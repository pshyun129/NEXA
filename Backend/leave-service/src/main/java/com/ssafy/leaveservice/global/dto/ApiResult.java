package com.ssafy.leaveservice.global.dto;

import com.ssafy.leaveservice.global.exception.ErrorCode;
import lombok.Getter;

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
}
