package com.ssafy.user_service.global.exception;

import lombok.Getter;

import java.util.Map;

/** 도메인/서비스 계층 예외. ErrorCode가 HTTP 상태와 응답 코드를 결정한다. */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    /** 오류 응답의 error.details 로 나가는 추가 정보. 없으면 비어 있다 */
    private final Map<String, Object> details;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), Map.of());
    }

    /** 기본 메시지 대신 상세 메시지를 쓰고 싶을 때 */
    public BusinessException(ErrorCode errorCode, String detailMessage) {
        this(errorCode, detailMessage, Map.of());
    }

    /** 기본 메시지 + 응답에 실을 추가 정보 (예: 잠금 해제 시각, 남은 시도 횟수) */
    public BusinessException(ErrorCode errorCode, Map<String, Object> details) {
        this(errorCode, errorCode.getMessage(), details);
    }

    public BusinessException(ErrorCode errorCode, String detailMessage, Map<String, Object> details) {
        super(detailMessage);
        this.errorCode = errorCode;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }
}
