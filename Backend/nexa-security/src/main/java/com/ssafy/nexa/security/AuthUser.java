package com.ssafy.nexa.security;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 요청한 사원. access 토큰의 sub(사원 ID), role, 소속(deptId/gradeId/dutyId) 클레임으로 만든다.
 * 소속 클레임이 없는 토큰이면 해당 값은 null 이다.
 */
public record AuthUser(Long employeeId, String role, Long deptId, Long gradeId, Long dutyId) {

    public static final String ADMIN_ROLE = "ADMIN";

    /** 소속 정보 없이 만든다. 1.0.0 과의 호환용 */
    public AuthUser(Long employeeId, String role) {
        this(employeeId, role, null, null, null);
    }

    public static AuthUser from(Jwt jwt) {
        return new AuthUser(
                Long.valueOf(jwt.getSubject()),
                jwt.getClaimAsString("role"),
                longClaim(jwt, "deptId"),
                longClaim(jwt, "gradeId"),
                longClaim(jwt, "dutyId"));
    }

    public boolean isAdmin() {
        return ADMIN_ROLE.equals(role);
    }

    // JSON 숫자는 크기에 따라 Integer/Long 으로 들어오므로 Number 로 받아 변환한다
    private static Long longClaim(Jwt jwt, String name) {
        Object value = jwt.getClaim(name);
        return value instanceof Number number ? number.longValue() : null;
    }
}
