package com.ssafy.user_service.domain.user;

import com.ssafy.user_service.support.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 부서 관리 API (관리자). dev seed 조직을 바꾸므로 테스트마다 롤백한다 (다른 테스트 클래스와 DB 공유).
 * 회사(13, 대표 109) > 경영지원본부(10, 107) > 인사팀(1, 102: 101 팀원)
 *                   > 기술본부(11, 108)     > 개발팀(2, 105: 103·104 팀원)
 *                   > 영업본부(12, 공석)     > 영업팀(3, 106)
 */
@Transactional
class DepartmentAdminIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/admin/departments";

    @Autowired
    MockMvc mvc;

    private Cookie[] login(String email) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"%s","pass":"admin1234!"}""".formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookies();
    }

    private ResultActions assignHead(Cookie[] user, long deptId, long empId) throws Exception {
        return mvc.perform(put(BASE + "/{deptId}/head", deptId).with(viaGateway(user))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"empId":%d}""".formatted(empId)));
    }

    private ResultActions changeType(Cookie[] user, long deptId, String type) throws Exception {
        return mvc.perform(put(BASE + "/{deptId}/type", deptId).with(viaGateway(user))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"deptType":"%s"}""".formatted(type)));
    }

    // ===== 목록 =====

    @Test
    void 목록은_부서ID_순이고_상위부서_유형_부서장을_담는다() throws Exception {
        mvc.perform(get(BASE).with(viaGateway(login("admin@local.dev"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(13))
                .andExpect(jsonPath("$.data[1].deptId").value(2))
                .andExpect(jsonPath("$.data[1].parentDeptId").value(11))
                .andExpect(jsonPath("$.data[1].deptType").value("TEAM"))
                .andExpect(jsonPath("$.data[1].headEmpId").value(105));
    }

    // ===== 부서장 =====

    @Test
    void 부서장을_바꾸면_조직_체인에_바로_반영된다() throws Exception {
        Cookie[] admin = login("admin@local.dev");
        assignHead(admin, 2, 104)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deptId").value(2))
                .andExpect(jsonPath("$.data.headEmpId").value(104));

        mvc.perform(get("/api/v1/employees/103/org-chain").with(viaGateway(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chain[0].headEmpId").value(104));
    }

    @Test
    void 하위_부서_소속도_부서장이_될_수_있다_겸직() throws Exception {
        // 영업팀장(106, 영업팀 소속)이 공석인 영업본부장을 겸한다
        assignHead(login("admin@local.dev"), 12, 106)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.headEmpId").value(106));
    }

    @Test
    void 다른_부서_소속은_400_DEPT_003() throws Exception {
        // 인사팀원(101)을 개발팀장으로
        assignHead(login("admin@local.dev"), 2, 101)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("DEPT_003"));
    }

    @Test
    void 퇴직자는_409_DEPT_002() throws Exception {
        Cookie[] admin = login("admin@local.dev");
        mvc.perform(patch("/api/v1/employees/104/terminate").with(viaGateway(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"resignDate":"2026-11-01"}"""))
                .andExpect(status().isOk());

        assignHead(admin, 2, 104)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DEPT_002"));
    }

    @Test
    void 없는_부서는_404_DEPT_001() throws Exception {
        assignHead(login("admin@local.dev"), 999999, 105)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DEPT_001"));
    }

    @Test
    void 부서장을_해제하면_공석() throws Exception {
        mvc.perform(delete(BASE + "/2/head").with(viaGateway(login("admin@local.dev"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.headEmpId").doesNotExist());
    }

    // ===== 부서 유형 =====

    @Test
    void 같은_단계로는_바꿀_수_있다_팀_아래_팀() throws Exception {
        // 경영지원본부(10)를 팀으로: 하위 팀들과 같은 단계
        changeType(login("admin@local.dev"), 10, "TEAM")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deptType").value("TEAM"));
    }

    @Test
    void 최상위가_아닌_부서는_회사가_될_수_없다_409_DEPT_004() throws Exception {
        changeType(login("admin@local.dev"), 10, "COMPANY")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DEPT_004"));
    }

    @Test
    void 최상위_부서는_회사여야_한다_409_DEPT_004() throws Exception {
        changeType(login("admin@local.dev"), 13, "DIVISION")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DEPT_004"));
    }

    @Test
    void 상위_부서보다_높은_단계는_409_DEPT_004() throws Exception {
        Cookie[] admin = login("admin@local.dev");
        changeType(admin, 10, "TEAM").andExpect(status().isOk());

        // 경영지원본부(10)가 팀이 된 뒤 그 아래 인사팀(1)을 본부로
        changeType(admin, 1, "DIVISION")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DEPT_004"));
    }

    @Test
    void 하위_부서보다_낮은_단계는_409_DEPT_004() throws Exception {
        Cookie[] admin = login("admin@local.dev");
        changeType(admin, 1, "DIVISION").andExpect(status().isOk());

        // 인사팀(1)이 본부가 된 뒤 그 위 경영지원본부(10)를 팀으로
        changeType(admin, 10, "TEAM")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DEPT_004"));
    }

    // ===== 권한 =====

    @Test
    void 일반_사용자는_403_AUTH_009() throws Exception {
        // 권한은 경로(/api/v1/admin/**) 기준이라 메서드마다 따로 보지 않는다
        assignHead(login("user3@local.dev"), 2, 103)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_009"));
    }
}
