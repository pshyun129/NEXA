package com.ssafy.user_service.domain.migration;

import com.ssafy.user_service.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * migration-service 용 내부 이관 API. 토큰 없이 호출한다 (migration RestClient 와 같음).
 * 트랜잭션 롤백 대신 매 테스트 뒤 batch 를 역순으로 되돌려 dev seed 를 원래대로 둔다 (다른 테스트 클래스와 DB 공유).
 * seed: 인사팀(1) / 개발팀(2), 직위 대리, 직책 팀장, 직급 G1~G6 × 1~5호봉, 직무 IT > 개발 > 백엔드
 */
class HrMigrationIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE = "/internal/v1/hr/migration";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private final Deque<String> batches = new ArrayDeque<>();

    @AfterEach
    void rollbackAll() throws Exception {
        while (!batches.isEmpty()) {
            mvc.perform(delete(BASE + "/batches/{id}", batches.pop())).andExpect(status().isOk());
        }
    }

    private ResultActions upsert(String entity, String batchId, String rowsJson) throws Exception {
        if (!batches.contains(batchId)) {
            batches.push(batchId);
        }
        return mvc.perform(post(BASE + "/{entity}/upsert", entity).param("batchId", batchId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"rows\":[" + rowsJson + "]}"));
    }

    private static String org(int rowNo, String code, String name, String parent, String head) {
        return """
                {"rowNo":%d,"data":{"deptCode":"%s","name":"%s","parentDeptCode":%s,"deptType":"TEAM","sortOrder":1,"headEmpNo":%s}}"""
                .formatted(rowNo, code, name, parent == null ? "null" : "\"" + parent + "\"",
                        head == null ? "null" : "\"" + head + "\"");
    }

    private static String emp(int rowNo, String empNo, String email, String dept, String position, String grade) {
        return """
                {"rowNo":%d,"data":{"empNo":"%s","name":"이관사원","email":"%s","dept":"%s","position":"%s",
                 "grade":"%s","duty":"팀장","jobFamily":"IT","jobKind":"개발","jobRole":"백엔드",
                 "hireDate":"2025-07-01","empType":"REGULAR","gender":"MALE"}}"""
                .formatted(rowNo, empNo, email, dept, position, grade);
    }

    private Map<String, Object> dept(String code) {
        return jdbc.queryForMap("SELECT * FROM department WHERE dept_code = ?", code);
    }

    private Long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    // ===== 기준정보 =====

    @Test
    void 기준정보는_없는_값만_만들고_사원이_그_값으로_들어가며_사원부터_되돌린다() throws Exception {
        upsert("references", "b-ref-1", """
                {"rowNo":1,"data":{"type":"POSITION","name":"이관직위","rankOrder":3}},
                {"rowNo":2,"data":{"type":"POSITION","name":"대리","rankOrder":99}},
                {"rowNo":3,"data":{"type":"GRADE","name":"이관직급","level":null}},
                {"rowNo":4,"data":{"type":"GRADE","name":"G1","level":1}},
                {"rowNo":5,"data":{"type":"DUTY","name":"이관직책"}},
                {"rowNo":6,"data":{"type":"JOB_CODE","family":"이관직군","kind":"이관직종","role":"이관직무"}},
                {"rowNo":7,"data":{"type":"JOB_CODE","family":"IT","kind":"개발","role":"이관직무2"}},
                {"rowNo":8,"data":{"type":"JOB_CODE","family":"IT","kind":"개발","role":"백엔드"}}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(5))
                .andExpect(jsonPath("$.data.updated").value(3));
        // 이미 있던 대리의 서열은 그대로, 대사에는 원본 행(직무 기준)만 센다
        assertThat(count("SELECT rank_order FROM position WHERE name = '대리'")).isEqualTo(3L);
        mvc.perform(get(BASE + "/batches/b-ref-1/summary")).andExpect(jsonPath("$.data.count").value(8));
        mvc.perform(get(BASE + "/references"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.positions[?(@.name == '이관직위')].rankOrder").value(3))
                .andExpect(jsonPath("$.data.duties[?(@.name == '이관직책')]").exists())
                .andExpect(jsonPath("$.data.jobCodes[?(@.name == '이관직종')].type").value("KIND"));

        upsert("employees", "b-emp-ref", """
                {"rowNo":2,"data":{"empNo":"M-0601","name":"이관사원","email":"m0601@migr.test","dept":"인사팀",
                 "position":"이관직위","grade":"이관직급","duty":"이관직책","jobFamily":"이관직군","jobKind":"이관직종",
                 "jobRole":"이관직무","hireDate":"2025-07-01"}}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(1));

        // 사원이 아직 쓰고 있으면 기준정보 batch 는 되돌릴 수 없다
        mvc.perform(delete(BASE + "/batches/b-ref-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MIG_003"));

        mvc.perform(delete(BASE + "/batches/b-emp-ref")).andExpect(status().isOk());
        batches.remove("b-emp-ref");
        mvc.perform(delete(BASE + "/batches/b-ref-1")).andExpect(status().isOk());
        batches.remove("b-ref-1");
        assertThat(count("SELECT COUNT(*) FROM job_code WHERE name LIKE '이관%'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM position WHERE name IN ('이관직위', '대리')")).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM grade WHERE name = '이관직급'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM duty WHERE name = '이관직책'")).isZero();
    }

    @Test
    void 기준정보_종류를_모르면_행번호와_함께_400() throws Exception {
        upsert("references", "b-ref-bad", """
                {"rowNo":9,"data":{"type":"TITLE","name":"이상한값"}}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MIG_001"))
                .andExpect(jsonPath("$.error.message").value(containsString("행 9")));
    }

    // ===== 조직 =====

    @Test
    void 조직은_부서코드로_생성하고_상위부서가_뒤쪽_묶음에_있어도_연결한다() throws Exception {
        // 첫 묶음: 하위 부서만 (상위 T-ROOT 는 아직 없음)
        upsert("orgs", "b-org-1", org(2, "T-CHILD", "이관하위팀", "T-ROOT", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.created").value(1))
                .andExpect(jsonPath("$.data.updated").value(0));
        assertThat(dept("T-CHILD").get("pending_parent_code")).isEqualTo("T-ROOT");

        // 다음 묶음: 상위 부서 → 이때 대기 중이던 하위가 연결된다
        upsert("orgs", "b-org-1", org(3, "T-ROOT", "이관본부", null, null))
                .andExpect(jsonPath("$.data.created").value(1));
        assertThat(dept("T-CHILD").get("parent_dept_id")).isEqualTo(dept("T-ROOT").get("dept_id"));
        assertThat(dept("T-CHILD").get("pending_parent_code")).isNull();

        mvc.perform(get(BASE + "/batches/b-org-1/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2));

        // 같은 코드를 다른 batch 로 다시 보내면 수정
        upsert("orgs", "b-org-2", org(2, "T-ROOT", "이름바뀐본부", null, null))
                .andExpect(jsonPath("$.data.created").value(0))
                .andExpect(jsonPath("$.data.updated").value(1));
        assertThat(dept("T-ROOT").get("name")).isEqualTo("이름바뀐본부");

        // b-org-2 를 되돌리면 이전 이름으로 복원
        mvc.perform(delete(BASE + "/batches/b-org-2")).andExpect(status().isOk());
        batches.remove("b-org-2");
        assertThat(dept("T-ROOT").get("name")).isEqualTo("이관본부");
    }

    @Test
    void 자기_자신을_상위로_지정하면_행번호와_함께_400() throws Exception {
        upsert("orgs", "b-org-self", org(5, "T-SELF", "자기참조", "T-SELF", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("MIG_001"))
                .andExpect(jsonPath("$.error.message").value(containsString("행 5")));
        assertThat(count("SELECT COUNT(*) FROM department WHERE dept_code = 'T-SELF'")).isZero();
    }

    // ===== 사원 =====

    @Test
    void 사원은_이름으로_기준정보를_찾고_부서장을_연결하며_되돌리면_원래대로() throws Exception {
        upsert("orgs", "b-org-e", org(2, "T-TEAM", "이관팀", null, "M-0001")).andExpect(status().isOk());
        assertThat(dept("T-TEAM").get("pending_head_emp_no")).isEqualTo("M-0001");
        String dev0101Before = jdbc.queryForObject("SELECT name || '/' || dept_id FROM employee WHERE emp_no = 'DEV0101'", String.class);

        upsert("employees", "b-emp-e",
                emp(2, "M-0001", "m0001@migr.test", "T-TEAM", "대리", "G3 2호봉") + ","
                        // 기존 사원: 부서를 이름(개발팀)으로, 직급은 이름만 있으면 모호하므로 호봉 포함
                        + """
                        {"rowNo":3,"data":{"empNo":"DEV0101","name":"이서연(수정)","dept":"개발팀","status":"ON_LEAVE"}}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(1))
                .andExpect(jsonPath("$.data.updated").value(1));

        Map<String, Object> created = jdbc.queryForMap("SELECT * FROM employee WHERE emp_no = 'M-0001'");
        assertThat(created.get("grade_id")).isEqualTo(12L);               // G3 2호봉 = (3-1)*5+2
        assertThat(created.get("role")).isEqualTo("USER");
        assertThat(created.get("status")).isEqualTo("EMPLOYED");
        assertThat(dept("T-TEAM").get("head_emp_id")).isEqualTo(created.get("emp_id"));
        assertThat(dept("T-TEAM").get("pending_head_emp_no")).isNull();
        assertThat(jdbc.queryForMap("SELECT name, dept_id, status FROM employee WHERE emp_no = 'DEV0101'"))
                .containsEntry("name", "이서연(수정)").containsEntry("dept_id", 2L).containsEntry("status", "ON_LEAVE");

        // 임의 비밀번호라 로그인 불가
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"m0001@migr.test\",\"pass\":\"admin1234!\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get(BASE + "/batches/b-emp-e/summary")).andExpect(jsonPath("$.data.count").value(2));

        // 사원 batch 되돌리기: 생성 삭제, 수정 복원, 부서장 연결도 이전(대기) 상태로
        mvc.perform(delete(BASE + "/batches/b-emp-e")).andExpect(status().isOk());
        batches.remove("b-emp-e");
        assertThat(count("SELECT COUNT(*) FROM employee WHERE emp_no = 'M-0001'")).isZero();
        assertThat(jdbc.queryForObject("SELECT name || '/' || dept_id FROM employee WHERE emp_no = 'DEV0101'", String.class))
                .isEqualTo(dev0101Before);
        assertThat(dept("T-TEAM").get("head_emp_id")).isNull();
        assertThat(dept("T-TEAM").get("pending_head_emp_no")).isEqualTo("M-0001");
        mvc.perform(get(BASE + "/batches/b-emp-e/summary")).andExpect(jsonPath("$.data.count").value(0));
    }

    @Test
    void 없는_직위는_행번호와_함께_실패하고_그_요청분은_저장되지_않는다() throws Exception {
        upsert("employees", "b-emp-bad",
                emp(6, "M-0101", "m0101@migr.test", "인사팀", "대리", "G1 1호봉") + ","
                        + emp(7, "M-0102", "m0102@migr.test", "인사팀", "없는직위", "G1 1호봉"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MIG_001"))
                .andExpect(jsonPath("$.error.message").value(containsString("행 7")))
                .andExpect(jsonPath("$.error.message").value(containsString("없는직위")));
        assertThat(count("SELECT COUNT(*) FROM employee WHERE emp_no IN ('M-0101','M-0102')")).isZero();
    }

    @Test
    void 호봉이_여러개인_직급을_이름만_주면_실패() throws Exception {
        upsert("employees", "b-emp-grade", emp(4, "M-0201", "m0201@migr.test", "인사팀", "대리", "G2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(containsString("호봉")));
    }

    @Test
    void 다른_사원의_이메일이면_실패() throws Exception {
        upsert("employees", "b-emp-mail", emp(2, "M-0301", "user1@local.dev", "인사팀", "대리", "G1 1호봉"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(containsString("DEV0101")));
    }

    @Test
    void 적재_중간에_실패해_일부만_남은_batch_도_되돌린다() throws Exception {
        upsert("employees", "b-emp-part", emp(2, "M-0401", "m0401@migr.test", "인사팀", "대리", "G1 1호봉"))
                .andExpect(status().isOk());
        upsert("employees", "b-emp-part", emp(502, "M-0402", "m0402@migr.test", "인사팀", "없는직위", "G1 1호봉"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/batches/b-emp-part/summary")).andExpect(jsonPath("$.data.count").value(1));

        mvc.perform(delete(BASE + "/batches/b-emp-part")).andExpect(status().isOk());
        batches.remove("b-emp-part");
        assertThat(count("SELECT COUNT(*) FROM employee WHERE emp_no LIKE 'M-04%'")).isZero();
    }

    @Test
    void 같은_행을_뒤_batch_가_다시_건드렸으면_앞_batch_는_먼저_못_되돌린다() throws Exception {
        String row = """
                {"rowNo":2,"data":{"empNo":"DEV0102","name":"%s"}}""";
        upsert("employees", "b-emp-a", row.formatted("첫번째")).andExpect(status().isOk());
        upsert("employees", "b-emp-b", row.formatted("두번째")).andExpect(status().isOk());

        mvc.perform(delete(BASE + "/batches/b-emp-a"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MIG_003"))
                .andExpect(jsonPath("$.error.message").value(containsString("b-emp-b")));
        // @AfterEach 가 b → a 순으로 되돌린다
    }

    @Test
    void 상위부서를_못_찾은_부서가_남아_있으면_사원_적재를_막는다() throws Exception {
        upsert("orgs", "b-org-orphan", org(2, "T-ORPHAN", "고아팀", "NO-SUCH", null)).andExpect(status().isOk());

        upsert("employees", "b-emp-orphan", emp(2, "M-0501", "m0501@migr.test", "인사팀", "대리", "G1 1호봉"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MIG_002"))
                .andExpect(jsonPath("$.error.message").value(containsString("T-ORPHAN(상위 NO-SUCH)")));
    }

    @Test
    void 없는_batch_되돌리기와_요약은_빈_결과() throws Exception {
        mvc.perform(delete(BASE + "/batches/b-none")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(get(BASE + "/batches/b-none/summary")).andExpect(jsonPath("$.data.count").value(0));
    }
}
