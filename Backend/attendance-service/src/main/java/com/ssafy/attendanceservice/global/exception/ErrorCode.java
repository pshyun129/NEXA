package com.ssafy.attendanceservice.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 인증/인가 (user-service와 코드 동일하게 유지)
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "AUTH_001", "인증이 필요합니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "AUTH_002", "토큰이 만료되었습니다."),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "AUTH_003", "유효하지 않은 토큰입니다."),
    TOKEN_BLACKLISTED(HttpStatus.UNAUTHORIZED, "AUTH_004", "로그아웃된 토큰입니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "AUTH_009", "접근 권한이 없습니다."),

    // 근태 (단계 진행하면서 추가)
    EMPLOYEE_NOT_FOUND(HttpStatus.NOT_FOUND, "ATTN_001", "존재하지 않는 사원입니다."),
    ALREADY_CLOCKED_IN(HttpStatus.CONFLICT, "ATTN_002", "이미 출근 처리되었습니다."),
    DEFAULT_WORK_GROUP_NOT_FOUND(HttpStatus.INTERNAL_SERVER_ERROR, "ATTN_003", "기본 근무그룹이 설정되지 않았습니다."),
    WORK_STATUS_CODE_NOT_FOUND(HttpStatus.INTERNAL_SERVER_ERROR, "ATTN_004", "근무 상태 코드가 설정되지 않았습니다."),
    NOT_CLOCKED_IN(HttpStatus.CONFLICT, "ATTN_005", "출근 기록이 없습니다."),
    ALREADY_CLOCKED_OUT(HttpStatus.CONFLICT, "ATTN_006", "이미 퇴근 처리되었습니다."),
    INVALID_STATUS_CHANGE(HttpStatus.CONFLICT, "ATTN_007", "변경할 수 없는 근무 상태입니다."),
    DUPLICATE_CLOCK_LOG(HttpStatus.CONFLICT, "ATTN_008", "해당 근무일에 이미 출근 또는 퇴근 기록이 있습니다."),
    WORK_STATUS_LOG_NOT_FOUND(HttpStatus.NOT_FOUND, "ATTN_009", "근무 상태 기록을 찾을 수 없습니다."),
    DUPLICATE_PENDING_REQUEST(HttpStatus.CONFLICT, "ATTN_010", "같은 날짜에 결재 대기 중인 신청이 있습니다."),
    WEEKLY_MAX_HOURS_EXCEEDED(HttpStatus.CONFLICT, "ATTN_011", "주 최대 근로시간을 초과합니다."),
    ATTENDANCE_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "ATTN_012", "근태 신청서를 찾을 수 없습니다."),
    APPROVAL_REQUEST_REJECTED(HttpStatus.BAD_REQUEST, "ATTN_013", "결재 문서를 만들 수 없습니다."),
    APPROVAL_CALL_FAILED(HttpStatus.BAD_GATEWAY, "ATTN_014", "결재 서비스 호출에 실패했습니다."),
    USER_CALL_FAILED(HttpStatus.BAD_GATEWAY, "ATTN_015", "사원 정보를 조회할 수 없습니다."),
    APPROVAL_CANCEL_REJECTED(HttpStatus.CONFLICT, "ATTN_016", "결재가 진행되어 회수할 수 없습니다."),
    REQUEST_NOT_PENDING(HttpStatus.CONFLICT, "ATTN_017", "결재 대기 중인 신청서만 회수할 수 있습니다."),
    LEAVE_DAY_CLOCK_IN(HttpStatus.CONFLICT, "ATTN_018", "휴가일에는 출근할 수 없습니다."),
    LEAVE_DAY_REQUEST(HttpStatus.CONFLICT, "ATTN_019", "휴가일에는 신청할 수 없습니다."),
    LEAVE_CALL_FAILED(HttpStatus.BAD_GATEWAY, "ATTN_020", "휴가 서비스 호출에 실패했습니다."),
    WORK_DAY_CLOSED(HttpStatus.CONFLICT, "ATTN_021", "퇴근을 찍지 않은 근무가 마감되었습니다. 근무시간 수정 신청으로 퇴근 시각을 넣어 주세요."),

    // 공통 (user-service와 동일)
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON_001", "요청 값이 올바르지 않습니다."),
    DATA_INTEGRITY_VIOLATION(HttpStatus.CONFLICT, "COMMON_002", "데이터 제약 조건에 위배됩니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON_003", "요청한 리소스를 찾을 수 없습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_999", "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}