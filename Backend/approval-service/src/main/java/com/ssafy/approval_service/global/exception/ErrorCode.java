package com.ssafy.approval_service.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 인증/인가 (토큰 검증 실패 응답은 nexa-security 가 직접 작성한다. 여기는 컨트롤러까지 온 예외용)
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "AUTH_001", "인증이 필요합니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "AUTH_009", "접근 권한이 없습니다."),

    // 결재
    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "APPROVAL_001", "문서를 찾을 수 없습니다."),
    DRAFTER_IN_APPROVAL_LINE(HttpStatus.BAD_REQUEST, "APPROVAL_002", "기안자는 결재선에 포함될 수 없습니다."),
    DUPLICATE_APPROVER(HttpStatus.BAD_REQUEST, "APPROVAL_003", "결재선에 같은 사원이 중복되었습니다."),
    DUPLICATE_APPROVAL_STEP(HttpStatus.BAD_REQUEST, "APPROVAL_004", "결재 순서가 중복되었습니다."),
    NOT_DRAFTER(HttpStatus.FORBIDDEN, "APPROVAL_005", "기안자만 변경할 수 있습니다."),
    DOCUMENT_NOT_IN_PROGRESS(HttpStatus.CONFLICT, "APPROVAL_006", "진행 중인 문서가 아닙니다."),
    PARTICIPANT_NOT_FOUND(HttpStatus.NOT_FOUND, "APPROVAL_007", "결재선에서 해당 사원을 찾을 수 없습니다."),
    ALREADY_ACTED(HttpStatus.CONFLICT, "APPROVAL_008", "이미 결재한 결재자는 변경할 수 없습니다."),
    INVALID_APPROVER_ROLE(HttpStatus.BAD_REQUEST, "APPROVAL_009", "결재자 역할은 APPROVER 또는 AGREEMENT만 가능합니다."),
    INVALID_APPROVER_ORDER(HttpStatus.BAD_REQUEST, "APPROVAL_010", "결재자 순서가 올바르지 않습니다."),
    LAST_APPROVER(HttpStatus.BAD_REQUEST, "APPROVAL_011", "결재자는 최소 1명 있어야 합니다."),
    NOT_YOUR_TURN(HttpStatus.FORBIDDEN, "APPROVAL_012", "현재 결재 차례가 아닙니다."),
    FINAL_APPROVE_NOT_ALLOWED(HttpStatus.FORBIDDEN, "APPROVAL_013", "전결은 결재자(APPROVER)만 할 수 있습니다."),
    DOCUMENT_NOT_REJECTED(HttpStatus.CONFLICT, "APPROVAL_014", "반려된 문서만 재기안할 수 있습니다."),
    EMPLOYEE_NOT_FOUND(HttpStatus.BAD_REQUEST, "APPROVAL_015", "존재하지 않는 사원입니다."),
    EMPLOYEE_NOT_ACTIVE(HttpStatus.BAD_REQUEST, "APPROVAL_016", "재직 중인 사원만 결재선에 지정할 수 있습니다."),
    EMPLOYEE_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "APPROVAL_017", "사원 정보를 확인할 수 없습니다. 잠시 후 다시 시도하세요."),
    INVALID_DOCUMENT_SOURCE(HttpStatus.BAD_REQUEST, "APPROVAL_018", "직접 기안이 아니면 원본 신청 ID(sourceId)가 필요하고, 직접 기안이면 sourceId를 보낼 수 없습니다."),
    FORM_SOURCE_MISMATCH(HttpStatus.BAD_REQUEST, "APPROVAL_019", "문서 양식과 출처가 맞지 않습니다. 휴가/근태 문서는 해당 신청을 통해서만 만들 수 있습니다."),
    REQUEST_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "APPROVAL_020", "다른 서비스의 신청으로 만드는 문서는 요청 키(requestKey)가 필요합니다."),
    REQUEST_KEY_CONFLICT(HttpStatus.CONFLICT, "APPROVAL_021", "같은 요청 키로 다른 신청의 문서가 이미 있습니다."),
    APPROVER_NOT_RESOLVED(HttpStatus.CONFLICT, "APPROVAL_022", "결재할 부서장을 찾지 못했습니다. 관리자에게 부서장 지정을 요청해 주세요."),
    SOURCE_DOCUMENT_NOT_RESUBMITTABLE(HttpStatus.CONFLICT, "APPROVAL_023", "다른 서비스(휴가·근태·업무 자동화)에서 만든 문서는 재기안할 수 없습니다. 원본 서비스에서 새로 신청하세요."),
    REQUIRED_APPROVER_FIXED(HttpStatus.CONFLICT, "APPROVAL_024", "위임전결 규정상 결재자는 결재선에서 빼거나 순서를 바꿀 수 없습니다."),
    REQUIRED_APPROVER_NOT_SKIPPABLE(HttpStatus.CONFLICT, "APPROVAL_025", "위임전결 규정상 결재자가 남아 있어 전결할 수 없습니다."),

    // 공통
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON_001", "요청 값이 올바르지 않습니다."),
    DATA_INTEGRITY_VIOLATION(HttpStatus.CONFLICT, "COMMON_002", "데이터 제약 조건에 위배됩니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON_003", "요청한 리소스를 찾을 수 없습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_999", "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
