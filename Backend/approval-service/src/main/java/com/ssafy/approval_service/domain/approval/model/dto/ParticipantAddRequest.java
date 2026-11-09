package com.ssafy.approval_service.domain.approval.model.dto;

import jakarta.validation.constraints.NotNull;

/** 참조자/열람자 추가 요청. 순서가 없으므로 사원 ID만 받는다 */
public record ParticipantAddRequest(
        @NotNull Long empId     // 추가할 사원ID
) {
}
