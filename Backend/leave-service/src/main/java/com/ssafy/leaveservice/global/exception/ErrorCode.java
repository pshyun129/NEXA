package com.ssafy.leaveservice.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 인증/인가
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "AUTH_001", "인증이 필요합니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "AUTH_009", "접근 권한이 없습니다."),
    AUTHENTICATION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "LEAVE_AUTH_001", "직원 인증 연동이 준비되지 않았습니다."),

    // 휴가
    BALANCE_TYPE_NOT_FOUND(HttpStatus.NOT_FOUND, "LEAVE_001", "존재하지 않는 휴가 잔액 유형입니다."),
    LEAVE_TYPE_NOT_FOUND(HttpStatus.NOT_FOUND, "LEAVE_002", "존재하지 않는 휴가 유형입니다."),
    LEAVE_TYPE_IN_USE(HttpStatus.CONFLICT, "LEAVE_003", "사용 중인 휴가 유형은 이름과 활성 여부만 변경할 수 있습니다."),
    LEAVE_POLICY_GROUP_NOT_FOUND(HttpStatus.NOT_FOUND, "LEAVE_004", "존재하지 않는 휴가 정책 그룹입니다."),
    LEAVE_POLICY_VERSION_NOT_FOUND(HttpStatus.NOT_FOUND, "LEAVE_005", "존재하지 않는 휴가 정책 버전입니다."),
    LEAVE_POLICY_VERSION_IN_USE(HttpStatus.CONFLICT, "LEAVE_006", "활성 상태이거나 사용 이력이 있는 휴가 정책 버전은 변경할 수 없습니다."),
    LEAVE_ACCRUAL_RULE_NOT_FOUND(HttpStatus.NOT_FOUND, "LEAVE_007", "존재하지 않는 연차 발생 규칙입니다."),
    LEAVE_POLICY_ACTIVATION_CONFLICT(HttpStatus.CONFLICT, "LEAVE_008", "활성화할 수 없는 휴가 정책 설정입니다."),
    LEAVE_POLICY_ASSIGNMENT_CONFLICT(HttpStatus.CONFLICT, "LEAVE_009", "직원 휴가 정책을 지정할 수 없는 상태이거나 배정 기간이 충돌합니다."),
    CURRENT_LEAVE_POLICY_NOT_FOUND(HttpStatus.NOT_FOUND, "LEAVE_010", "현재 적용되는 휴가 정책 배정이 없습니다."),
    CURRENT_LEAVE_POLICY_CONFLICT(HttpStatus.CONFLICT, "LEAVE_011", "현재 적용 휴가 정책의 배정 또는 활성 버전 상태가 올바르지 않습니다."),
    LEAVE_POLICY_TYPE_RULE_CONFLICT(HttpStatus.CONFLICT, "LEAVE_012", "현재 휴가 정책의 신청 규칙과 휴가 유형 설정이 일치하지 않습니다."),

    LEAVE_REQUEST_NOT_ALLOWED(HttpStatus.CONFLICT, "LEAVE_013", "휴가를 신청할 수 없는 상태입니다."),
    LEAVE_SUBMISSION_KEY_CONFLICT(HttpStatus.CONFLICT, "LEAVE_014", "같은 신청 키에 다른 요청 내용을 사용할 수 없습니다."),
    HOLIDAY_VERIFICATION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "LEAVE_015", "공휴일 검증을 완료하지 못했습니다."),

    APPROVAL_INTEGRATION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "LEAVE_016", "결재 연동이 준비되지 않아 휴가 신청을 사용할 수 없습니다."),

    HR_VERIFICATION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "LEAVE_017", "인사 정보 또는 부서 조회 권한을 확인할 수 없습니다."),

    LEAVE_ADJUSTMENT_NOT_ALLOWED(HttpStatus.CONFLICT, "LEAVE_018", "현재 발생분에는 잔액 조정을 적용할 수 없습니다."),
    LEAVE_ADJUSTMENT_KEY_CONFLICT(HttpStatus.CONFLICT, "LEAVE_019", "같은 조정 키에 다른 요청 내용을 사용할 수 없습니다."),

    APPROVAL_WITHDRAWAL_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "LEAVE_020", "결재 철회 연동이 준비되지 않아 관리자 취소를 사용할 수 없습니다."),
    LEAVE_CANCELLATION_NOT_ALLOWED(HttpStatus.CONFLICT, "LEAVE_021", "현재 휴가 신청 또는 근태 원복 상태에서는 관리자 취소를 완료할 수 없습니다."),
    LEAVE_CANCELLATION_CONFLICT(HttpStatus.CONFLICT, "LEAVE_022", "이미 시작된 취소의 관리자 또는 사유와 일치하지 않습니다."),

    LEAVE_GRANT_NOT_ALLOWED(HttpStatus.CONFLICT, "LEAVE_023", "현재 직원 정책 또는 지급 이력에는 요청한 수동 지급을 적용할 수 없습니다."),
    LEAVE_GRANT_KEY_CONFLICT(HttpStatus.CONFLICT, "LEAVE_024", "같은 지급 키에 다른 요청 내용을 사용할 수 없습니다."),

    ATTENDANCE_REVERT_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "LEAVE_025", "근태 원복 연동이 준비되지 않아 관리자 취소를 사용할 수 없습니다."),

    // 공통: user-service와 동일한 상태 및 코드
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON_001", "요청 값이 올바르지 않습니다."),
    DATA_INTEGRITY_VIOLATION(HttpStatus.CONFLICT, "COMMON_002", "데이터 제약 조건에 위배됩니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON_003", "요청한 리소스를 찾을 수 없습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_999", "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
