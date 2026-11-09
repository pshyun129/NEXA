package com.ssafy.approval_service.domain.approval.policy;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * 전결 단계를 정하는 데 필요한 신청 값. 원본 서비스(leave·attendance)나 결재 화면이 생성 요청에 담아 보낸다.
 * approval 은 다른 업무 서비스에 이 값을 묻지 않는다. 상신 시점 값을 문서에 그대로 저장한다(스냅샷).
 * 모두 선택 항목이고, 없으면 해당 조건은 충족하지 않은 것으로 본다 (기본 전결권자)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApprovalAttributes(
        @PositiveOrZero BigDecimal days,                // 휴가 일수 (반차 0.5). LEAVE
        @PositiveOrZero Integer overtimeMinutes,        // 이번 초과근무 신청 시간(분). OVERTIME, 참고용
        @PositiveOrZero Integer monthlyOvertimeMinutes, // 이번 신청을 포함한 그 달 초과근무 누적(분). OVERTIME
        Boolean overseas,                               // 해외 출장 여부. BUSINESS_TRIP
        @PositiveOrZero Long amount                     // 금액(원). GENERAL
) {
    public static final ApprovalAttributes EMPTY = new ApprovalAttributes(null, null, null, null, null);

    /** null 이면 빈 값 */
    public static ApprovalAttributes orEmpty(ApprovalAttributes attributes) {
        return attributes == null ? EMPTY : attributes;
    }

    /** 값이 하나도 없으면 true (저장하지 않음). JSON 필드가 아니다 */
    @JsonIgnore
    public boolean isEmpty() {
        return days == null && overtimeMinutes == null && monthlyOvertimeMinutes == null
                && overseas == null && amount == null;
    }
}
