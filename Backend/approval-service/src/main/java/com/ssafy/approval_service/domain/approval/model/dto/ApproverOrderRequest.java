package com.ssafy.approval_service.domain.approval.model.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** 결재자 순서 변경 요청. 바뀐 순서대로 결재자 사원 ID를 나열하며, 앞에서부터 step 1, 2, ... 로 다시 매긴다 */
public record ApproverOrderRequest(
        @NotEmpty List<@NotNull Long> empIds
) {
}
