package com.ssafy.migrationservice.global.exception;

import lombok.Getter;

/** 도메인/서비스 계층 예외. ErrorCode가 HTTP 상태와 응답 코드를 결정한다. */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 기본 메시지 대신 상세 메시지를 쓰고 싶을 때 */
    public BusinessException(ErrorCode errorCode, String detailMessage) {
        super(detailMessage);
        this.errorCode = errorCode;
    }
}
