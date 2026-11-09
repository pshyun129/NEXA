package com.ssafy.leaveservice.global.auth;

import com.ssafy.nexa.security.AuthUser;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** 요청한 사원. nexa-security 가 검증한 access 토큰의 sub(사원 ID)와 role 에서 꺼낸다 */
@lombok.RequiredArgsConstructor
@Component
public class TokenEmployeeAccess implements EmployeeAccess {
    private final LeaveAdministratorAccess administratorAccess;

    @Override
    public Long currentEmployeeId() {
        AuthUser user = currentUser();
        return user == null ? null : user.employeeId(); // null 이면 각 서비스가 401 AUTH_001
    }

    @Override
    public boolean isAdmin(Long employeeId) {
        AuthUser user = currentUser();
        return user != null && user.employeeId().equals(employeeId) && administratorAccess.isAdministrator(employeeId);
    }

    private static AuthUser currentUser() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken auth
                ? AuthUser.from(auth.getToken()) : null;
    }
}
