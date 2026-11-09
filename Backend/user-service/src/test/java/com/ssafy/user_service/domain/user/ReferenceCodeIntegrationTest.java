package com.ssafy.user_service.domain.user;

import com.jayway.jsonpath.JsonPath;
import com.ssafy.user_service.support.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 코드 목록 API (직위·직급·직책·직무 코드). 값은 R__dev_seed.sql 기준 */
class ReferenceCodeIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    private Cookie[] user;

    @BeforeEach
    void login() throws Exception {
        // 일반 사원도 조회할 수 있어야 하므로 관리자가 아닌 시드 계정으로 확인한다
        user = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user1@local.dev","pass":"admin1234!"}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookies();
    }

    private String body(String path) throws Exception {
        return mvc.perform(get(path).with(viaGateway(user)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void 직위는_서열순() throws Exception {
        String body = body("/api/v1/positions");
        List<String> names = JsonPath.read(body, "$.data[*].name");
        assertThat(names).startsWith("사원", "주임", "대리", "과장");
        assertThat(names.getLast()).isEqualTo("대표이사");
        assertThat((Integer) JsonPath.read(body, "$.data[0].rankOrder")).isEqualTo(1);
    }

    @Test
    void 직급은_직급_호봉_순() throws Exception {
        String body = body("/api/v1/grades");
        List<Integer> ids = JsonPath.read(body, "$.data[*].gradeId");
        assertThat(ids).isSorted().hasSize(30);
        assertThat((String) JsonPath.read(body, "$.data[0].name")).isEqualTo("G1");
        assertThat((Integer) JsonPath.read(body, "$.data[1].level")).isEqualTo(2);
    }

    @Test
    void 직책_목록() throws Exception {
        String body = body("/api/v1/duties");
        List<Integer> ids = JsonPath.read(body, "$.data[*].dutyId");
        assertThat(ids).isSorted().isNotEmpty();
        assertThat((String) JsonPath.read(body, "$.data[0].name")).isEqualTo("팀원");
    }

    @Test
    void 직무_코드는_직군_직종_직무_순이고_type으로_거를_수_있다() throws Exception {
        String all = body("/api/v1/job-codes");
        List<String> types = JsonPath.read(all, "$.data[*].type");
        assertThat(types.indexOf("KIND")).isGreaterThan(types.lastIndexOf("FAMILY"));
        assertThat(types.indexOf("ROLE")).isGreaterThan(types.lastIndexOf("KIND"));

        String kinds = body("/api/v1/job-codes?type=KIND");
        assertThat((List<String>) JsonPath.read(kinds, "$.data[*].type")).containsOnly("KIND");
        // 인사(2) → 상위 경영지원(1)
        assertThat((List<Integer>) JsonPath.read(kinds, "$.data[?(@.jobCodeId == 2)].parentId")).containsExactly(1);
    }

    @Test
    void 잘못된_type은_400() throws Exception {
        mvc.perform(get("/api/v1/job-codes").param("type", "WRONG").with(viaGateway(user)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }

    @Test
    void 경로_변수_타입이_틀리면_500이_아니라_400() throws Exception {
        mvc.perform(get("/api/v1/employees/abc").with(viaGateway(user)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }

    @Test
    void 인증이_없으면_401() throws Exception {
        for (String path : List.of("/api/v1/positions", "/api/v1/grades", "/api/v1/duties", "/api/v1/job-codes")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }
}
