package com.ssafy.approval_service.support;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * 통합 테스트 공통 요청 재료: 인증(jwt() 흉내), 결재선 JSON, 문서 경로.
 * 사원 ID 대역은 테스트 클래스끼리 겹치지 않게 각 클래스가 따로 관리한다.
 */
public final class ApprovalTestFixtures {

    public static final String BASE = "/api/v1/documents";

    private ApprovalTestFixtures() {
    }

    public static RequestPostProcessor user(long empId) {
        return jwt().jwt(j -> j.subject(String.valueOf(empId)).claim("type", "access").claim("role", "USER"));
    }

    public static RequestPostProcessor admin(long empId) {
        return jwt().jwt(j -> j.subject(String.valueOf(empId)).claim("type", "access").claim("role", "ADMIN"));
    }

    public static String line(long empId, int step, String role) {
        return """
                {"empId":%d,"step":%d,"role":"%s"}""".formatted(empId, step, role);
    }

    public static String path(long documentId, String sub) {
        return BASE + "/" + documentId + sub;
    }
}
