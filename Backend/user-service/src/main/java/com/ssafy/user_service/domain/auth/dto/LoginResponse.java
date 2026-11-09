package com.ssafy.user_service.domain.auth.dto;

public record LoginResponse(
        Long employeeId,  // 회원번호
        String deptName,  // 부서명
        String gradeName  // 직급명
) {
}
