package com.ssafy.user_service.global.security;

/** 보안 설정과 컨트롤러가 함께 참조하는 경로/쿠키 이름. global이 domain(컨트롤러)에 의존하지 않도록 여기 둔다. */
public final class SecurityConstants {

    public static final String AUTH_BASE_PATH = "/api/v1/auth";
    public static final String ADMIN_BASE_PATH = "/api/v1/admin";
    public static final String EMPLOYEE_BASE_PATH = "/api/v1/employees";
    public static final String DUTY_BASE_PATH = "/api/v1/duties";
    public static final String POSITION_BASE_PATH = "/api/v1/positions";
    public static final String GRADE_BASE_PATH = "/api/v1/grades";
    public static final String JOB_CODE_BASE_PATH = "/api/v1/job-codes";
    public static final String JWKS_PATH = "/.well-known/jwks.json";
    /** migration-service 전용 내부 API. gateway 라우트가 없어 외부로 노출되지 않는다 */
    public static final String INTERNAL_MIGRATION_PATH = "/internal/v1/hr/migration";

    public static final String ACCESS_COOKIE = "accessToken";
    public static final String REFRESH_COOKIE = "refreshToken";

    private SecurityConstants() {
    }
}
