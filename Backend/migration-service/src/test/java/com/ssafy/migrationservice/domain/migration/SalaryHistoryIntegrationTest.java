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

/** 연봉이력 ↔ 급여 기본급 ↔ 4대보험 연봉(연도) */
class SalaryHistoryIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/admin/migrations";

    // 사람·연도별 행. 김민수 2026년은 작년 연봉 그대로, 이영희 2025년 인상률은 %로 적음
    private static final String HISTORY = """
            사번,성명,연도,연봉,인상률,월 기본급
            00123,홍길동,2025,48000000,,4000000
            00123,홍길동,2026,49440000,0.03,4120000
            00124,김민수,2025,42000000,,3500000
            00124,김민수,2026,42000000,0,3500000
            00125,이영희,2025,36000000,3.5,3000000
            00125,이영희,2026,37080000,0.03,3090000
            00126,박지민,2026,36000000,0.025,3000000
            00127,최유진,2026,36000000,0.04,3000000
            """;

    private static final String PAYROLL_DEC = """
            사번,성명,기본급,식대
            00123,홍길동,4120000,200000
            00124,김민수,3605000,200000
            00125,이영희,3090000,200000
            """;

    private static final String INSURANCE = """
            사번,성명,연봉(2026),보수월액
            00123,홍길동,49440000,4120000
            00124,김민수,43260000,3605000
            00125,이영희,36000000,3000000
            """;

    @Autowired
    MockMvc mvc;

    @Test
    void 연봉이력을_급여_기본급과_4대보험_연봉과_대조하고_인상률_퍼센트_표기를_찾는다() throws Exception {
        long migrationId = create();
        long historyId = upload(migrationId, "연봉이력.csv", HISTORY);
        upload(migrationId, "급여대장_2026-12.csv", PAYROLL_DEC);     // 연봉이력보다 나중에 올려도 대조된다
        long insuranceId = upload(migrationId, "4대보험.csv", INSURANCE);

        assertThat(codes(migrationId, historyId)).containsExactlyInAnyOrder(
                "6:RAISE_RATE_AS_PERCENT", "5:SALARY_PAYROLL_MISMATCH");
        assertThat(codes(migrationId, insuranceId)).containsExactlyInAnyOrder(
                "3:SALARY_HISTORY_MISMATCH", "4:SALARY_HISTORY_MISMATCH");
    }

    private List<String> codes(long migrationId, long fileId) throws Exception {
        String body = mvc.perform(get(BASE + "/{id}/issues", migrationId).param("fileId", String.valueOf(fileId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> issues = JsonPath.read(body, "$.data.content");
        return issues.stream().map(i -> i.get("rowNo") + ":" + i.get("code")).toList();
    }

    private long create() throws Exception {
        String created = mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("""
                        {"companyId":1,"name":"연봉이력 테스트"}"""))
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
