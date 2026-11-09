package com.ssafy.user_service.domain.auth;

import com.ssafy.user_service.support.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 로그인/재발급/로그아웃/잠금 흐름. dev 시드의 admin@local.dev 계정을 사용한다. */
class AuthFlowIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN_LOGIN = """
            {"email":"admin@local.dev","pass":"admin1234!"}""";

    @Autowired
    MockMvc mvc;

    private Cookie[] login(String body) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly("accessToken", true))
                .andExpect(cookie().path("refreshToken", "/api/v1/auth"))
                .andReturn();
        return res.getResponse().getCookies();
    }

    private static String loginJson(String email, String pass) {
        return """
                {"email":"%s","pass":"%s"}""".formatted(email, pass);
    }

    @Test
    void 로그인_성공시_요약정보와_쿠키를_내려준다() throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(ADMIN_LOGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.employeeId").value(1))
                .andExpect(jsonPath("$.data.deptName").isString())
                .andReturn();
        assertThat(res.getResponse().getCookies()).extracting(Cookie::getName).contains("accessToken", "refreshToken");
    }

    @Test
    void 재발급_회전과_재사용_탐지_그리고_이전_access_차단() throws Exception {
        Cookie[] first = login(ADMIN_LOGIN);

        MvcResult reissued = mvc.perform(post("/api/v1/auth/reissue").cookie(first))
                .andExpect(status().isOk()).andReturn();
        Cookie[] second = reissued.getResponse().getCookies();
        assertThat(second).extracting(Cookie::getName).contains("accessToken", "refreshToken");

        // 옛 refresh 재사용 → 세션 폐기
        mvc.perform(post("/api/v1/auth/reissue").cookie(first))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_008"));
        // 교체된 이전 access 는 즉시 차단
        mvc.perform(post("/api/v1/nothing").with(viaGateway(first)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_004"));
        // 재사용 탐지로 새 refresh 도 무효
        mvc.perform(post("/api/v1/auth/reissue").cookie(second))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_008"));
    }

    @Test
    void 로그아웃_후_access는_블랙리스트_refresh는_무효_쿠키는_삭제() throws Exception {
        Cookie[] c = login(ADMIN_LOGIN);
        mvc.perform(post("/api/v1/auth/logout").cookie(c))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge("accessToken", 0))
                .andExpect(cookie().maxAge("refreshToken", 0));
        mvc.perform(post("/api/v1/nothing").with(viaGateway(c)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_004"));
        mvc.perform(post("/api/v1/auth/reissue").cookie(c))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_008"));
        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isOk()); // 쿠키 없이도 200
    }

    @Test
    void 로그인_5회_실패시_계정_잠금_423() throws Exception {
        // 잠금은 계정 존재 여부와 무관하게 loginId 기준으로 센다
        // 실패마다 남은 시도 횟수(4 → 0)를 알려 준다. 5번째 실패로 잠기면 0
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(loginJson("lock@local.dev", "wrong")))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("AUTH_005"))
                    .andExpect(jsonPath("$.error.details.remainingAttempts").value(4 - i));
        }
        // 잠긴 뒤에는 실제 남은 시간(설정 15분 이내)과 해제 시각, Retry-After 헤더
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("lock@local.dev", "whatever")))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error.code").value("AUTH_010"))
                .andExpect(jsonPath("$.error.details.retryAfterSeconds").value(
                        org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.greaterThan(0), org.hamcrest.Matchers.lessThanOrEqualTo(900))))
                .andExpect(jsonPath("$.error.details.lockedUntil").value(org.hamcrest.Matchers.endsWith("+09:00")))
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void 매핑되지_않은_URL은_404() throws Exception {
        Cookie[] admin = login(ADMIN_LOGIN);
        mvc.perform(post("/api/v1/nothing").with(viaGateway(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COMMON_003"))
                .andExpect(jsonPath("$.error.details").doesNotExist());   // details 가 없는 오류는 형식이 예전과 같다
    }

    @Test
    void 보호된_API는_쿠키만으로는_401_Bearer_헤더가_필요() throws Exception {
        // 쿠키 → Bearer 변환은 gateway 가 한다. gateway 를 거치지 않고 직접 호출하면 헤더를 붙여야 한다
        Cookie[] admin = login(ADMIN_LOGIN);
        mvc.perform(get("/api/v1/employees").cookie(admin))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_001"));
        mvc.perform(get("/api/v1/employees").with(viaGateway(admin)))
                .andExpect(status().isOk());
    }
}
