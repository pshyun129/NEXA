package com.ssafy.user_service.domain.user.dto;

import jakarta.validation.constraints.NotNull;

/** 부서장 지정 요청 (관리자 기능) */
public record DepartmentHeadRequest(
        @NotNull Long empId     // 부서장으로 지정할 사원ID
) {
}
