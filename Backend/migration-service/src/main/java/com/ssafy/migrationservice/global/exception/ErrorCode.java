package com.ssafy.migrationservice.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 이관 작업
    MIGRATION_NOT_FOUND(HttpStatus.NOT_FOUND, "MIG_001", "존재하지 않는 이관 작업입니다."),
    MIGRATION_FILE_NOT_FOUND(HttpStatus.NOT_FOUND, "MIG_002", "존재하지 않는 업로드 파일입니다."),
    MIGRATION_COMPLETED(HttpStatus.CONFLICT, "MIG_003", "전환 완료된 이관 작업은 변경할 수 없습니다."),
    STAGING_ROW_NOT_FOUND(HttpStatus.NOT_FOUND, "MIG_004", "존재하지 않는 행입니다."),
    FILE_ALREADY_LOADED(HttpStatus.CONFLICT, "MIG_005", "적재된 파일은 수정할 수 없습니다. 되돌린 뒤 수정하세요."),

    // 적재
    BLOCK_ISSUES_EXIST(HttpStatus.CONFLICT, "LOAD_001", "차단 오류를 먼저 해결해야 합니다."),
    LOAD_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "LOAD_002", "아직 적재를 지원하지 않는 데이터입니다."),
    NOTHING_TO_LOAD(HttpStatus.BAD_REQUEST, "LOAD_003", "적재할 파일이 없습니다."),
    BATCH_NOT_FOUND(HttpStatus.NOT_FOUND, "LOAD_004", "존재하지 않는 적재 묶음입니다."),
    BATCH_NOT_LOADED(HttpStatus.CONFLICT, "LOAD_005", "적재 완료 상태의 묶음만 되돌릴 수 있습니다."),
    ROLLBACK_DEPENDENCY_EXISTS(HttpStatus.CONFLICT, "LOAD_006", "이 묶음에 의존하는 묶음을 먼저 되돌려야 합니다."),
    TARGET_CALL_FAILED(HttpStatus.BAD_GATEWAY, "LOAD_007", "대상 서비스 호출에 실패했습니다."),
    FIX_PENDING(HttpStatus.CONFLICT, "LOAD_008", "자동 정리를 먼저 적용해야 합니다."),
    REFERENCE_AMBIGUOUS(HttpStatus.CONFLICT, "LOAD_009", "사원 적재 때 하나로 고를 수 없는 기준정보가 있습니다."),

    // 파일
    FILE_EMPTY(HttpStatus.BAD_REQUEST, "FILE_001", "빈 파일입니다."),
    FILE_TYPE_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "FILE_002", "xlsx, csv 파일만 업로드할 수 있습니다."),
    FILE_READ_FAILED(HttpStatus.BAD_REQUEST, "FILE_003", "파일을 읽을 수 없습니다."),
    FILE_NO_HEADER(HttpStatus.BAD_REQUEST, "FILE_004", "헤더 행을 찾을 수 없습니다."),
    FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "FILE_005", "파일 크기가 너무 큽니다."),
    SHEET_NOT_FOUND(HttpStatus.BAD_REQUEST, "FILE_006", "파일에 그 이름의 시트가 없습니다."),

    // LLM
    LLM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "LLM_001", "LLM을 사용할 수 없습니다. 키 설정과 연결을 확인하세요."),

    // 공통
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON_001", "요청 값이 올바르지 않습니다."),
    DATA_INTEGRITY_VIOLATION(HttpStatus.CONFLICT, "COMMON_002", "데이터 제약 조건에 위배됩니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON_003", "요청한 리소스를 찾을 수 없습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_999", "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
