package com.ssafy.migrationservice.domain.migration;

import com.jayway.jsonpath.JsonPath;
import com.ssafy.migrationservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 업로드 → 매핑 추천 → 검증 → 원인별 묶음 → 매핑 수정 후 재검증 */
class ValidationIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/admin/migrations";

    // 실제 회사 명부처럼: 필수 컬럼 일부 없음, 날짜 표기 섞임, 입사일 빈 칸, 사번 중복
    private static final String EMPLOYEE_CSV = """
            사번,이름,부서,직위,입사년월일,기본급,비고
            00123,홍길동,개발1팀,과장,2024-01-02,3600000,
            00124,김민수,개발1팀,대리,2023.3.15,3100000,육아휴직
            00125,이영희,인사팀,사원,20250701,2800000,
            00126,박철수,개발2팀,과정,,3300000,
            00123,김민수,영업팀,차장,2019-11-04,4200000,
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private long createJob() throws Exception {
        String body = mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"companyId":1,"name":"검증 테스트"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.data.migrationId")).longValue();
    }

    private long upload(long migrationId) throws Exception {
        String body = mvc.perform(multipart(BASE + "/{id}/files", migrationId)
                        .file(new MockMultipartFile("file", "직원.csv", "text/csv", EMPLOYEE_CSV.getBytes(StandardCharsets.UTF_8)))
                        .param("entity", "HR_EMPLOYEE"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.missingRequired",
                        containsInAnyOrder("이메일", "직급", "직책", "직군", "직종", "직무")))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.data.fileId")).longValue();
    }

    @Test
    void 업로드하면_매핑_추천과_검증이_돌고_원인별로_묶인다() throws Exception {
        long migrationId = createJob();
        long fileId = upload(migrationId);

        mvc.perform(get(BASE + "/{id}/files/{fileId}", migrationId, fileId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.columnMappings[0].sourceColumn").value("사번"))
                .andExpect(jsonPath("$.data.columnMappings[0].targetField").value("empNo"))
                .andExpect(jsonPath("$.data.columnMappings[4].targetLabel").value("입사일"))
                .andExpect(jsonPath("$.data.columnMappings[5].targetField").doesNotExist());

        String issues = mvc.perform(get(BASE + "/{id}/issues", migrationId).param("fileId", String.valueOf(fileId)))
                .andExpect(status().isOk())
                // 필수 컬럼 없음(한 건으로 묶음) 1 + 입사일 빈 칸 1 + 사번 중복 2 = BLOCK 4 / 날짜 표기 2 = FIX 2
                .andExpect(jsonPath("$.data.totals.BLOCK").value(4))
                .andExpect(jsonPath("$.data.totals.FIX").value(2))
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> groups = JsonPath.read(issues, "$.data.groups");
        Map<String, Object> missing = groups.stream().filter(g -> "MISSING_REQUIRED_COLUMN".equals(g.get("code"))).findFirst().orElseThrow();
        // 직급 열은 없지만 직위가 있어 직위를 직급으로 쓴다 (필수 누락에서 빠지고 파일 단위 경고)
        assertThat((String) missing.get("message")).startsWith("필수 컬럼 5개가 없습니다: 이메일, 직책, 직군, 직종, 직무");
        assertThat(groups).anySatisfy(g -> assertThat(g.get("code")).isEqualTo("GRADE_FROM_POSITION"));
        assertThat(groups).anySatisfy(g -> assertThat(g.get("code")).isEqualTo("GRADE_STEP_DEFAULTED"));

        Map<String, Object> required = groups.stream().filter(g -> "REQUIRED".equals(g.get("code"))).findFirst().orElseThrow();
        assertThat((String) required.get("message")).isEqualTo("입사일이 비어 있습니다");

        Map<String, Object> dup = groups.stream().filter(g -> "DUPLICATE_KEY".equals(g.get("code"))).findFirst().orElseThrow();
        assertThat(((Number) dup.get("count")).intValue()).isEqualTo(2);
        assertThat(dup.get("sampleRows")).isEqualTo(List.of(2, 6));
        assertThat((String) dup.get("message")).isEqualTo("사번 중복: '00123' (2, 6행)");

        Map<String, Object> dateFix = groups.stream().filter(g -> "DATE_FORMAT".equals(g.get("code"))).findFirst().orElseThrow();
        assertThat(((Number) dateFix.get("count")).intValue()).isEqualTo(2);
        assertThat(dateFix.get("sourceColumn")).isEqualTo("입사년월일");
        @SuppressWarnings("unchecked")
        List<String> sampleValues = (List<String>) dateFix.get("sampleValues");
        assertThat(sampleValues).containsExactlyInAnyOrder("2023.3.15", "20250701");
    }

    @Test
    void 매핑을_고치면_다시_검증한다() throws Exception {
        long migrationId = createJob();
        long fileId = upload(migrationId);

        // 입사일 연결을 끊으면: 입사일 필수 컬럼 없음 +1, 입사일 빈 칸·날짜 표기 오류는 사라짐
        mvc.perform(put(BASE + "/{id}/files/{fileId}/mappings", migrationId, fileId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"columnMappings":[{"sourceColumn":"입사년월일","targetField":null}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.missingRequired", containsInAnyOrder("이메일", "직급", "직책", "직군", "직종", "직무", "입사일")))
                .andExpect(jsonPath("$.data.issueTotals.BLOCK").value(3))
                .andExpect(jsonPath("$.data.issueTotals.FIX").value(0));

        mvc.perform(get(BASE + "/{id}/files/{fileId}", migrationId, fileId))
                .andExpect(jsonPath("$.data.columnMappings[4].confirmed").value(true));
    }

    @Test
    void 자동_정리를_일괄_적용하면_값이_바뀌고_FIX가_사라진다() throws Exception {
        long migrationId = createJob();
        long fileId = upload(migrationId);

        mvc.perform(post(BASE + "/{id}/fixes", migrationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(2))
                .andExpect(jsonPath("$.data.issueTotals.FIX").value(0))
                .andExpect(jsonPath("$.data.issueTotals.BLOCK").value(4));

        // 바뀐 값은 진단에서 날짜 표기 한 종류로 보인다
        mvc.perform(get(BASE + "/{id}/files/{fileId}", migrationId, fileId))
                .andExpect(jsonPath("$.data.columns[4].dateFormats").value(containsInAnyOrder("YYYY-MM-DD")));

        // 다시 눌러도 바꿀 게 없다
        mvc.perform(post(BASE + "/{id}/fixes", migrationId))
                .andExpect(jsonPath("$.data.applied").value(0));

        // 변경 이력: 원래 값 → 바뀐 값
        List<Map<String, Object>> changes = jdbcTemplate.queryForList(
                "SELECT row_no, old_value, new_value, change_type FROM staging_change WHERE file_id = ? ORDER BY row_no", fileId);
        assertThat(changes).extracting(c -> c.get("old_value") + "→" + c.get("new_value") + "/" + c.get("change_type"))
                .containsExactly("2023.3.15→2023-03-15/AUTO_FIX", "20250701→2025-07-01/AUTO_FIX");
    }

    @Test
    void 묶음_하나만_골라_적용할_수_있다() throws Exception {
        long migrationId = createJob();
        long fileId = upload(migrationId);

        mvc.perform(post(BASE + "/{id}/fixes", migrationId).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileId":%d,"code":"NUMBER_FORMAT"}""".formatted(fileId)))
                .andExpect(jsonPath("$.data.applied").value(0))
                .andExpect(jsonPath("$.data.issueTotals.FIX").value(2));

        mvc.perform(post(BASE + "/{id}/fixes", migrationId).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileId":%d,"code":"DATE_FORMAT","field":"hireDate"}""".formatted(fileId)))
                .andExpect(jsonPath("$.data.applied").value(2))
                .andExpect(jsonPath("$.data.issueTotals.FIX").value(0));
    }

    @Test
    void 화면에서_행을_고치면_그_행만_다시_확인한다() throws Exception {
        long migrationId = createJob();
        long fileId = upload(migrationId);

        // 5행: 입사일 빈 칸 → 채움
        mvc.perform(patch(BASE + "/{id}/files/{fileId}/rows/{rowNo}", migrationId, fileId, 5)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"values":{"입사년월일":"2022-05-01"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowIssues").isEmpty())
                .andExpect(jsonPath("$.data.issueTotals.BLOCK").value(3));

        // 6행: 중복 사번 → 새 사번. 2행·6행 중복이 함께 풀린다
        mvc.perform(patch(BASE + "/{id}/files/{fileId}/rows/{rowNo}", migrationId, fileId, 6)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"values":{"사번":"00128"}}"""))
                .andExpect(jsonPath("$.data.issueTotals.BLOCK").value(1));

        assertThat(jdbcTemplate.queryForList(
                "SELECT old_value, new_value FROM staging_change WHERE file_id = ? AND change_type = 'MANUAL' ORDER BY change_id", fileId))
                .extracting(c -> c.get("old_value") + "→" + c.get("new_value"))
                .containsExactly("null→2022-05-01", "00123→00128");

        // 없는 행 404, 없는 컬럼 400
        mvc.perform(patch(BASE + "/{id}/files/{fileId}/rows/{rowNo}", migrationId, fileId, 99)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"values":{"사번":"1"}}"""))
                .andExpect(status().isNotFound());
        mvc.perform(patch(BASE + "/{id}/files/{fileId}/rows/{rowNo}", migrationId, fileId, 5)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"values":{"없는컬럼":"1"}}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 잘못된_매핑은_400() throws Exception {
        long migrationId = createJob();
        long fileId = upload(migrationId);

        mvc.perform(put(BASE + "/{id}/files/{fileId}/mappings", migrationId, fileId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"columnMappings":[{"sourceColumn":"비고","targetField":"empNo"}]}"""))
                .andExpect(status().isBadRequest());

        mvc.perform(put(BASE + "/{id}/files/{fileId}/mappings", migrationId, fileId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"columnMappings":[{"sourceColumn":"비고","targetField":"없는필드"}]}"""))
                .andExpect(status().isBadRequest());
    }
}
