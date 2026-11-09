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

/** 여러 달이 세로로 쌓인 급여: 퇴사 후 지급, 음수 실지급액, 빠진 달 */
class PayrollRowIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/admin/migrations";

    private static final String ROSTER = """
            사번,이름,이메일,부서,직위,직급,직책,직군,직종,직무,입사일,퇴사일
            00123,홍길동,a@x.com,개발팀,과장,G3,팀원,개발,SW,백엔드,2024-01-02,
            00124,김민수,b@x.com,개발팀,대리,G2,팀원,개발,SW,백엔드,2023-03-15,2026-08-15
            00125,이영희,c@x.com,인사팀,사원,G1,팀원,경영지원,인사,채용,2025-07-01,
            """;

    // 홍길동 9월 행 없음, 김민수 8/15 퇴사인데 9월 지급, 이영희 9월 실지급액 음수
    private static final String PAYROLL = """
            귀속월,사번,이름,기본급,지급합계,공제합계,실지급액
            2026-07,00123,홍길동,3000000,3000000,300000,2700000
            2026-08,00123,홍길동,3000000,3000000,300000,2700000
            2026-10,00123,홍길동,6000000,6000000,600000,5400000
            2026-08,00124,김민수,2500000,2500000,250000,2250000
            2026-09,00124,김민수,2500000,2500000,250000,2250000
            2026-08,00125,이영희,2400000,2400000,240000,2160000
            2026-09,00125,이영희,0,0,"559,070","-559,070"
            """;

    @Autowired
    MockMvc mvc;

    @Test
    void 퇴사_후_지급_음수_실지급액_빠진_달을_찾는다() throws Exception {
        String created = mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("""
                        {"companyId":1,"name":"급여 행 테스트"}"""))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long migrationId = ((Number) JsonPath.read(created, "$.data.migrationId")).longValue();
        upload(migrationId, "직원.csv", ROSTER, "HR_EMPLOYEE");
        long fileId = upload(migrationId, "급여_2026하반기.csv", PAYROLL, "REFERENCE");

        String body = mvc.perform(get(BASE + "/{id}/issues", migrationId).param("fileId", String.valueOf(fileId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> issues = JsonPath.read(body, "$.data.content");

        assertThat(issues).extracting(i -> i.get("rowNo") + ":" + i.get("code")).contains(
                "4:PAY_MONTH_GAP", "6:PAID_OUTSIDE_EMPLOYMENT", "8:NEGATIVE_NET_PAY");
        assertThat(issues).extracting(i -> (String) i.get("code"))
                .filteredOn(c -> c.equals("PAY_MONTH_GAP") || c.equals("PAID_OUTSIDE_EMPLOYMENT") || c.equals("NEGATIVE_NET_PAY"))
                .hasSize(3);
    }

    private long upload(long migrationId, String name, String csv, String entity) throws Exception {
        String body = mvc.perform(multipart(BASE + "/{id}/files", migrationId)
                        .file(new MockMultipartFile("file", name, "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .param("entity", entity))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.data.fileId")).longValue();
    }
}
