package com.ssafy.user_service.domain.user.dto;

/** 로그인 응답 등에 쓰이는 사원 요약 (부서명/직급명 포함) */
public record EmployeeSummary(
        Long employeeId,
        String name,
        String deptName,
        String gradeName
) {
}
