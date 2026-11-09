package com.ssafy.user_service.domain.user;

import com.ssafy.user_service.support.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 조직 체인 조회 API. dev seed 의 조직을 그대로 쓴다.
 * 회사(13, 대표 109) > 기술본부(11, 본부장 108) > 개발팀(2, 팀장 105)
 * 회사(13) > 영업본부(12, 공석) > 영업팀(3, 팀장 106)
 */
class OrganizationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    private Cookie[] login(String email) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"%s","pass":"admin1234!"}""".formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookies();
    }

    @Test
    void 팀원의_체인은_팀_본부_회사_순서이고_각_부서장이_있다() throws Exception {
        mvc.perform(get("/api/v1/employees/103/org-chain").with(viaGateway(login("user3@local.dev"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.employeeId").value(103))
                .andExpect(jsonPath("$.data.deptId").value(2))
                .andExpect(jsonPath("$.data.chain.length()").value(3))
                .andExpect(jsonPath("$.data.chain[0].deptId").value(2))
                .andExpect(jsonPath("$.data.chain[0].deptType").value("TEAM"))
                .andExpect(jsonPath("$.data.chain[0].headEmpId").value(105))
                .andExpect(jsonPath("$.data.chain[1].deptId").value(11))
                .andExpect(jsonPath("$.data.chain[1].deptType").value("DIVISION"))
                .andExpect(jsonPath("$.data.chain[1].headEmpId").value(108))
                .andExpect(jsonPath("$.data.chain[2].deptId").value(13))
                .andExpect(jsonPath("$.data.chain[2].deptType").value("COMPANY"))
                .andExpect(jsonPath("$.data.chain[2].headEmpId").value(109));
    }

}
