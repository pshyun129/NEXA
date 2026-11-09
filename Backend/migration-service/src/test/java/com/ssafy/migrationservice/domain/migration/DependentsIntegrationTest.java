package com.ssafy.migrationservice.domain.migration;

import com.jayway.jsonpath.JsonPath;
import com.ssafy.migrationservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 부양가족 기본공제 나이 요건, 원천징수비율, 공제가족수 ↔ 급여대장 */
class DependentsIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/admin/migrations";

    private static final String DEPENDENTS = """
            사번,직원명,관계,성명,나이,기본공제
            00123,홍길동,배우자,김영희,40,Y
            00123,홍길동,자녀,홍민준,21,Y
            00124,김민수,모,박순자,55,Y
            00124,김민수,부,김철수,58,N
            00125,이영희,자녀,이서연,10,Y
            """;

    // 사번 숫자로 저장(앞자리 0 빠짐)된 행 포함
    private static final String SUMMARY = """
            사번,성명,공제대상가족수(본인포함),원천징수비율(%)
            00123,홍길동,3,100
            124,김민수,2,90
            00125,이영희,2,80
            """;

    private static final String PAYROLL_NOV = """
            사번,성명,공제 가족수,기본급
            00123,홍길동,1,3000000
            00124,김민수,1,3000000
            00125,이영희,1,3000000
            """;

    private static final String PAYROLL_DEC = """
            사번,성명,공제 가족수,기본급
            00123,홍길동,3,3000000
            00124,김민수,1,3000000
            00125,이영희,2,3000000
            """;

    @Autowired
    MockMvc mvc;

    @Test
    void 기본공제_나이_요건과_원천징수비율을_본다() throws Exception {
        long migrationId = create();
        long fileId = upload(migrationId, "부양가족명세.csv", DEPENDENTS);
        long summaryId = upload(migrationId, "공제대상집계.csv", SUMMARY);

        assertThat(codes(migrationId, fileId)).containsExactlyInAnyOrder("3:DEPENDENT_AGE_RULE", "4:DEPENDENT_AGE_RULE");
        assertThat(codes(migrationId, summaryId)).containsExactly("3:WITHHOLDING_RATIO_INVALID");
    }

    @Test
    void 공제가족수를_가장_늦은_달_급여대장과_대조하고_급여대장을_나중에_올려도_다시_본다() throws Exception {
        long migrationId = create();
        long summaryId = upload(migrationId, "공제대상집계.csv", SUMMARY);
        assertThat(codes(migrationId, summaryId)).doesNotContain("2:FAMILY_COUNT_MISMATCH");

        upload(migrationId, "급여대장_2026-11.csv", PAYROLL_NOV);
        long decId = upload(migrationId, "급여대장_2026-12.csv", PAYROLL_DEC);

        // 12월 기준: 김민수(124 → 00124)만 다름. 월별 급여 시트에는 같은 경고를 반복하지 않는다
        assertThat(codes(migrationId, summaryId)).filteredOn(c -> c.endsWith("FAMILY_COUNT_MISMATCH"))
                .containsExactly("3:FAMILY_COUNT_MISMATCH");
        assertThat(codes(migrationId, decId)).noneMatch(c -> c.endsWith("FAMILY_COUNT_MISMATCH"));
    }

    private List<String> codes(long migrationId, long fileId) throws Exception {
        String body = mvc.perform(get(BASE + "/{id}/issues", migrationId).param("fileId", String.valueOf(fileId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> issues = JsonPath.read(body, "$.data.content");
        return issues.stream().map(i -> i.get("rowNo") + ":" + i.get("code")).toList();
    }

    private long create() throws Exception {
        String created = mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("""
                        {"companyId":1,"name":"부양가족 테스트"}"""))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(created, "$.data.migrationId")).longValue();
    }

    private long upload(long migrationId, String name, String csv) throws Exception {
        String body = mvc.perform(multipart(BASE + "/{id}/files", migrationId)
                        .file(new MockMultipartFile("file", name, "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .param("entity", "REFERENCE"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.data.fileId")).longValue();
    }
}
