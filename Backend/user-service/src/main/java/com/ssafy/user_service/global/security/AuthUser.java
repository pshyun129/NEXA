package com.ssafy.user_service.global.security;

import com.ssafy.user_service.global.security.Role;

/** SecurityContext에 등록되는 인증 주체. 컨트롤러에서 @AuthenticationPrincipal AuthUser 로 받는다. */
public record AuthUser(Long employeeId, Role role) {
}
