package com.ssafy.migrationservice.domain.migration;

import com.jayway.jsonpath.JsonPath;
import com.ssafy.migrationservice.support.AbstractIntegrationTest;
import com.ssafy.migrationservice.support.FakeHrServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 적재(기준정보 → 조직 → 사원) → 대사 → 되돌리기. 인사 서비스는 가짜 서버(실제 HTTP)로 대신한다. */
class LoadIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/admin/migrations";
    private static final FakeHrServer HR = new FakeHrServer();

    private static final String ORG_CSV = """
            부서코드,부서명,상위부서
            D010,개발본부,
            D100,개발1팀,D010
            D200,인사팀,D010
            """;

    // 필수 컬럼 모두 있음. 입사일 '2024.1.2'는 FIX라 자동 정리를 적용해야 적재할 수 있다
    private static final String EMP_CSV = """
            사번,이름,이메일,부서,직위,직급,직책,직군,직종,직무,입사일,직원구분
            00123,홍길동,hong@nexa.com,개발1팀,과장,G3,팀원,개발,SW,백엔드,2024.1.2,정규직
            00124,김민수,kim@nexa.com,인사팀,대리,G2,팀원,경영지원,인사,채용,2023-03-15,계약직
            """;

    @DynamicPropertySource
    static void hrUrl(DynamicPropertyRegistry registry) {
        registry.add("migration.targets.hr.base-url", HR::baseUrl);
    }

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void resetHr() {
        HR.reset();
    }

    private long createJob() throws Exception {
        String body = mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"companyId":1,"name":"적재 테스트"}"""))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.data.migrationId")).longValue();
    }

    private void applyFixes(long migrationId) throws Exception {
        mvc.perform(post(BASE + "/{id}/fixes", migrationId)).andExpect(status().isOk());
    }

    private long upload(long migrationId, String entity, String csv) throws Exception {
        String body = mvc.perform(multipart(BASE + "/{id}/files", migrationId)
                        .file(new MockMultipartFile("file", entity + ".csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .param("entity", entity))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.data.fileId")).longValue();
    }

    @Test
    void 차단_오류가_있으면_적재하지_않는다() throws Exception {
        long id = createJob();
        upload(id, "HR_EMPLOYEE", "사번,이름\n00123,홍길동\n");

        mvc.perform(post(BASE + "/{id}/loads", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LOAD_001"));
        assertThat(HR.rows("employees")).isEmpty();
    }

    @Test
    void 미리보기는_보낼_순서와_정리된_값만_보여주고_보내지_않는다() throws Exception {
        long id = createJob();
        upload(id, "HR_EMPLOYEE", EMP_CSV);
        upload(id, "HR_ORG", ORG_CSV);

        mvc.perform(post(BASE + "/{id}/loads", id).param("dryRun", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loadable").value(false))
                .andExpect(jsonPath("$.data.fixIssues").value(1))
                .andExpect(jsonPath("$.data.files[*].entity").value(contains("HR_CODE", "HR_ORG", "HR_EMPLOYEE")))
                // 기준정보: 사원 파일의 직위 2 + 직급 2 + 직책 1 + 직무 경로 2
                .andExpect(jsonPath("$.data.files[0].rows").value(7))
                .andExpect(jsonPath("$.data.files[0].sample[0].type").value("POSITION"))
                .andExpect(jsonPath("$.data.files[0].sample[0].name").value("과장"))
                .andExpect(jsonPath("$.data.files[0].sample[0].rankOrder").value(4))
                .andExpect(jsonPath("$.data.files[2].sample[0].hireDate").value("2024-01-02"))
                .andExpect(jsonPath("$.data.files[2].sample[0].empType").value("REGULAR"));
        assertThat(HR.rows("orgs")).isEmpty();
    }

    @Test
    void 자동_정리가_남아_있으면_적재하지_않고_적용하면_적재된다() throws Exception {
        long id = createJob();
        upload(id, "HR_ORG", ORG_CSV);
        upload(id, "HR_EMPLOYEE", EMP_CSV);

        mvc.perform(post(BASE + "/{id}/loads", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LOAD_008"))
                .andExpect(jsonPath("$.error.message").value("자동 정리 1건을 먼저 적용하세요"));
        assertThat(HR.rows("orgs")).isEmpty();

        applyFixes(id);
        mvc.perform(post(BASE + "/{id}/loads", id).param("dryRun", "true"))
                .andExpect(jsonPath("$.data.loadable").value(true));
        mvc.perform(post(BASE + "/{id}/loads", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("LOADED"));
    }

    @Test
    void 조직부터_적재하고_대사가_맞는다() throws Exception {
        long id = createJob();
        long empFile = upload(id, "HR_EMPLOYEE", EMP_CSV);   // 직원을 먼저 올려도
        upload(id, "HR_ORG", ORG_CSV);
        applyFixes(id);

        mvc.perform(post(BASE + "/{id}/loads", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("LOADED"))
                .andExpect(jsonPath("$.data.batches[0].entity").value("HR_CODE"))     // 사원 파일의 기준정보가 먼저
                .andExpect(jsonPath("$.data.batches[0].fileId").value(empFile))
                .andExpect(jsonPath("$.data.batches[0].created").value(7))
                .andExpect(jsonPath("$.data.batches[1].entity").value("HR_ORG"))      // 그다음 조직
                .andExpect(jsonPath("$.data.batches[1].status").value("LOADED"))
                .andExpect(jsonPath("$.data.batches[1].created").value(3))
                .andExpect(jsonPath("$.data.batches[2].entity").value("HR_EMPLOYEE"))
                .andExpect(jsonPath("$.data.batches[2].created").value(2));

        // 기준정보는 값마다 한 행, 직급은 이름·호봉으로 나눠 보낸다
        assertThat(HR.rows("references")).extracting(r -> r.data().get("type").asString() + ":" + r.data().path("name").asString(""))
                .contains("POSITION:과장", "POSITION:대리", "GRADE:G3", "GRADE:G2", "DUTY:팀원", "JOB_CODE:");

        // 인사 서비스에는 정리된 값이 들어간다
        FakeHrServer.Stored hong = HR.rows("employees").stream()
                .filter(s -> s.data().get("empNo").asString().equals("00123")).findFirst().orElseThrow();
        assertThat(hong.data().get("hireDate").asString()).isEqualTo("2024-01-02");
        assertThat(hong.data().get("empType").asString()).isEqualTo("REGULAR");
        // 부서명은 같은 작업 조직 파일의 부서코드로 바꿔 보낸다 (대상에 같은 이름의 기존 부서가 있어도 고를 수 있게)
        assertThat(hong.data().get("dept").asString()).isEqualTo("D100");

        mvc.perform(get(BASE + "/{id}/reconciliation", id))
                .andExpect(jsonPath("$.data[*].match").value(contains(true, true, true)))
                .andExpect(jsonPath("$.data[0].source").value(7))
                .andExpect(jsonPath("$.data[2].source").value(2))
                .andExpect(jsonPath("$.data[2].loaded").value(2));

        mvc.perform(get(BASE + "/{id}", id))
                .andExpect(jsonPath("$.data.batches.length()").value(3));
        // 기준정보까지 들어갔으니 비교할 것이 없다
        mvc.perform(get(BASE + "/{id}/references", id))
                .andExpect(jsonPath("$.data.items.length()").value(0));

        // 적재된 파일은 되돌리기 전까지 수정 불가
        mvc.perform(put(BASE + "/{id}/files/{fileId}/mappings", id, empFile).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"columnMappings":[{"sourceColumn":"직원구분","targetField":null}]}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MIG_005"));

        // 이미 적재된 파일만 남았으면 더 넣을 게 없다
        mvc.perform(post(BASE + "/{id}/loads", id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("LOAD_003"));
    }

    @Test
    void 의존하는_묶음이_있으면_되돌릴_수_없고_역순으로_되돌린다() throws Exception {
        long id = createJob();
        upload(id, "HR_ORG", ORG_CSV);
        upload(id, "HR_EMPLOYEE", EMP_CSV);
        applyFixes(id);
        String loaded = mvc.perform(post(BASE + "/{id}/loads", id)).andReturn().getResponse().getContentAsString();
        String codeBatch = JsonPath.read(loaded, "$.data.batches[0].batchId");
        String orgBatch = JsonPath.read(loaded, "$.data.batches[1].batchId");
        String empBatch = JsonPath.read(loaded, "$.data.batches[2].batchId");

        mvc.perform(delete(BASE + "/{id}/batches/{batchId}", id, orgBatch))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LOAD_006"));
        mvc.perform(delete(BASE + "/{id}/batches/{batchId}", id, codeBatch))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LOAD_006"));

        mvc.perform(delete(BASE + "/{id}/batches/{batchId}", id, empBatch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("LOADED"));
        assertThat(HR.rows("employees")).isEmpty();

        mvc.perform(delete(BASE + "/{id}/batches/{batchId}", id, orgBatch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("LOADED"));
        assertThat(HR.rows("orgs")).isEmpty();

        mvc.perform(delete(BASE + "/{id}/batches/{batchId}", id, codeBatch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
        assertThat(HR.rows("references")).isEmpty();

        // 되돌린 묶음은 다시 되돌릴 수 없다
        mvc.perform(delete(BASE + "/{id}/batches/{batchId}", id, orgBatch))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LOAD_005"));
    }

    @Test
    void 중간에_실패하면_그_묶음을_자동으로_되돌리고_뒤는_진행하지_않는다() throws Exception {
        long id = createJob();
        upload(id, "HR_ORG", ORG_CSV.replace("D200,인사팀,D010", "D200,FAIL,D010"));
        upload(id, "HR_EMPLOYEE", EMP_CSV);
        applyFixes(id);

        String body = mvc.perform(post(BASE + "/{id}/loads", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("LOADED"))      // 앞의 기준정보 묶음은 남아 있다
                .andExpect(jsonPath("$.data.batches.length()").value(2))
                .andExpect(jsonPath("$.data.batches[0].entity").value("HR_CODE"))
                .andExpect(jsonPath("$.data.batches[1].status").value("FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.data.batches[1].error")).contains("인사 서비스 호출 실패");

        // 실패 전에 들어간 D010, D100도 자동으로 지워졌다
        assertThat(HR.rows("orgs")).isEmpty();
        assertThat(HR.rows("employees")).isEmpty();
        List<?> batches = JsonPath.read(body, "$.data.batches");
        assertThat(batches).hasSize(2);
    }

    @Test
    void 기준정보는_대상과_비교해_보여주고_호봉을_못_고르는_직급이_있으면_적재하지_않는다() throws Exception {
        HR.existingGrade("G3", 1);
        HR.existingGrade("G3", 2);
        long id = createJob();
        upload(id, "HR_ORG", ORG_CSV);
        upload(id, "HR_EMPLOYEE", EMP_CSV.replace(",G2,", ",G2 3호봉,"));
        applyFixes(id);

        mvc.perform(get(BASE + "/{id}/references", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.value == 'G3')].status").value(contains("AMBIGUOUS")))
                .andExpect(jsonPath("$.data.items[?(@.value == 'G2 3호봉')].status").value(contains("NEW")))
                .andExpect(jsonPath("$.data.items[?(@.value == '개발 > SW > 백엔드')].type").value(contains("JOB_CODE")));

        mvc.perform(post(BASE + "/{id}/loads", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LOAD_009"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("G3 1호봉")));
        assertThat(HR.rows("references")).isEmpty();
        assertThat(HR.rows("orgs")).isEmpty();
    }
}
