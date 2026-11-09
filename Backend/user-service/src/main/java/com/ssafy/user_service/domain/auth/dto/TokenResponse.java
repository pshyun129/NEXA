package com.ssafy.user_service.domain.auth.dto;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        Long employeeId, // 회원번호
        String deptName, // 부서명
        String gradeName // 직급명
) {
}
