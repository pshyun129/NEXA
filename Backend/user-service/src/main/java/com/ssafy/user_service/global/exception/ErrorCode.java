package com.ssafy.user_service.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 인증/인가
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "AUTH_001", "인증이 필요합니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "AUTH_002", "토큰이 만료되었습니다."),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "AUTH_003", "유효하지 않은 토큰입니다."),
    TOKEN_BLACKLISTED(HttpStatus.UNAUTHORIZED, "AUTH_004", "로그아웃된 토큰입니다."),
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "AUTH_005", "이메일 또는 비밀번호가 올바르지 않습니다."),
    ACCOUNT_TERMINATED(HttpStatus.UNAUTHORIZED, "AUTH_006", "퇴직 처리된 계정입니다."),
    REFRESH_TOKEN_MISSING(HttpStatus.UNAUTHORIZED, "AUTH_007", "refreshToken이 없습니다."),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "AUTH_008", "유효하지 않은 refreshToken입니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "AUTH_009", "접근 권한이 없습니다."),
    ACCOUNT_LOCKED(HttpStatus.LOCKED, "AUTH_010", "로그인 실패가 반복되어 계정이 잠시 잠겼습니다. 잠시 후 다시 시도하세요."),

    // 사원
    EMPLOYEE_NOT_FOUND(HttpStatus.NOT_FOUND, "EMP_001", "존재하지 않는 사원입니다."),
    DUPLICATE_LOGIN_ID(HttpStatus.CONFLICT, "EMP_002", "이미 사용 중인 이메일입니다."),
    DEPARTMENT_NOT_FOUND(HttpStatus.BAD_REQUEST, "EMP_003", "존재하지 않는 부서입니다."),
    GRADE_NOT_FOUND(HttpStatus.BAD_REQUEST, "EMP_004", "존재하지 않는 직급입니다."),
    POSITION_NOT_FOUND(HttpStatus.BAD_REQUEST, "EMP_005", "존재하지 않는 직위입니다."),
    DUTY_NOT_FOUND(HttpStatus.BAD_REQUEST, "EMP_006", "존재하지 않는 직책입니다."),
    JOB_CODE_NOT_FOUND(HttpStatus.BAD_REQUEST, "EMP_007", "존재하지 않거나 종류가 맞지 않는 직군/직종/직무 코드입니다."),
    EMPLOYEE_ALREADY_TERMINATED(HttpStatus.CONFLICT, "EMP_008", "이미 퇴직 처리된 사원입니다."),

    // 부서 관리
    DEPT_NOT_FOUND(HttpStatus.NOT_FOUND, "DEPT_001", "존재하지 않는 부서입니다."),
    DEPT_HEAD_NOT_EMPLOYED(HttpStatus.CONFLICT, "DEPT_002", "재직 중인 사원만 부서장으로 지정할 수 있습니다."),
    DEPT_HEAD_NOT_MEMBER(HttpStatus.BAD_REQUEST, "DEPT_003", "부서장은 그 부서나 하위 부서 소속이어야 합니다."),
    DEPT_TYPE_HIERARCHY(HttpStatus.CONFLICT, "DEPT_004", "부서 유형이 상위·하위 부서와 맞지 않습니다."),

    // 이관 (migration-service 내부 API). 메시지에 원본 행 번호가 들어가며 이관 화면에 그대로 표시된다
    MIGRATION_ROW_INVALID(HttpStatus.BAD_REQUEST, "MIG_001", "이관 행 값이 올바르지 않습니다."),
    MIGRATION_PENDING_PARENT(HttpStatus.CONFLICT, "MIG_002", "상위 부서를 찾지 못한 부서가 남아 있습니다."),
    MIGRATION_ROLLBACK_CONFLICT(HttpStatus.CONFLICT, "MIG_003", "이 batch 는 되돌릴 수 없습니다."),
    MIGRATION_COMPANY_AMBIGUOUS(HttpStatus.CONFLICT, "MIG_004", "부서를 붙일 회사를 하나로 정할 수 없습니다."),

    // 공통
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON_001", "요청 값이 올바르지 않습니다."),
    DATA_INTEGRITY_VIOLATION(HttpStatus.CONFLICT, "COMMON_002", "데이터 제약 조건에 위배됩니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON_003", "요청한 리소스를 찾을 수 없습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_999", "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
