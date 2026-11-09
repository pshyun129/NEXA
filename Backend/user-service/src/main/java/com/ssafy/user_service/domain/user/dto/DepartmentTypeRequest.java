package com.ssafy.user_service.domain.user.dto;

import com.ssafy.user_service.domain.user.entity.enums.DeptType;
import jakarta.validation.constraints.NotNull;

/** 부서 유형 변경 요청 (관리자 기능) */
public record DepartmentTypeRequest(
        @NotNull DeptType deptType  // COMPANY | DIVISION | TEAM
) {
}
