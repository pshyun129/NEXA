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

/** 직원명부가 아닌 파일의 사번·이름·날짜를 같은 작업의 직원명부와 대조 */
class ReferenceIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/admin/migrations";

    private static final String ROSTER = """
            사번,이름,이메일,부서,직위,직급,직책,직군,직종,직무,입사일
            00123,홍길동,a@x.com,개발팀,과장,G3,팀원,개발,SW,백엔드,2024-01-02
            00124,김민수,b@x.com,개발팀,대리,G2,팀원,개발,SW,백엔드,2023-03-15
            00125,이영희,c@x.com,인사팀,사원,G1,팀원,경영지원,인사,채용,2025-07-01
            """;

    // 근태기록 같은 참고 자료: 앞자리 0 빠진 사번, 명부에 없는 사번, 사번 빈 칸(이름만), 이름 다름
    private static final String ATTENDANCE = """
            사번,성명,근무일,출근,퇴근
            00123,홍길동,2026-12-01,09:00,18:00
            124,김민수,2026-12-01,09:00,18:00
            00999,박철수,2026-12-01,09:00,18:00
            ,이영희,2026-12-01,09:00,18:00
            00125,이영이,2026-12-02,09:00,18:00
            """;

    private static final String ROSTER_WITH_RESIGN = """
            사번,이름,이메일,부서,직위,직급,직책,직군,직종,직무,입사일,퇴사일
            00123,홍길동,a@x.com,개발팀,과장,G3,팀원,개발,SW,백엔드,2024-01-02,
            00124,김민수,b@x.com,개발팀,대리,G2,팀원,개발,SW,백엔드,2023-03-15,
            00125,이영희,c@x.com,인사팀,사원,G1,팀원,경영지원,인사,채용,2025-07-01,2026-06-30
            00126,박지민,d@x.com,인사팀,사원,G1,팀원,경영지원,인사,채용,2025-08-01,2026-03-31
            00127,최유진,e@x.com,인사팀,사원,G1,팀원,경영지원,인사,채용,2025-09-01,2020-01-01
            00128,정우성,f@x.com,인사팀,사원,G1,팀원,경영지원,인사,채용,2021-05-01,
            """;

    // 정상 / 취득일 늦음(두 보험 모두) / 상실일 = 퇴사일 / 상실일 빠짐 / 명부 퇴사일이 입사일보다 빠름(명부 오류라 보지 않음) / 입사일 다름
    private static final String INSURANCE = """
            사번,성명,입사일,국민연금 취득일,건강보험 취득일,상실일,상실사유
            00123,홍길동,2024-01-02,2024-01-02,2024-01-02,,
            00124,김민수,2023-03-15,2023-04-20,2023-04-20,,
            00125,이영희,2025-07-01,2025-07-01,2025-07-01,2026-06-30,자진퇴사
            00126,박지민,2025-08-01,2025-08-01,2025-08-01,,
            00127,최유진,2025-09-01,2025-09-01,2025-09-01,,
            00128,정우성,2022-05-01,2021-05-01,2021-05-01,,
            """;

    @Autowired
    MockMvc mvc;

    @Test
    void 입사일_4대보험_취득일_상실일을_직원명부와_대조한다() throws Exception {
        long migrationId = create("날짜 대조 테스트");
        upload(migrationId, "직원.csv", ROSTER_WITH_RESIGN, "HR_EMPLOYEE");
        long fileId = upload(migrationId, "4대보험취득상실.csv", INSURANCE, "REFERENCE");

        String body = mvc.perform(get(BASE + "/{id}/issues", migrationId).param("fileId", String.valueOf(fileId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> issues = JsonPath.read(body, "$.data.content");

        assertThat(issues).extracting(i -> i.get("rowNo") + ":" + i.get("code")).containsExactlyInAnyOrder(
                "3:INSURANCE_ACQ_DATE_MISMATCH", "4:INSURANCE_LOSS_DATE_MISMATCH", "5:INSURANCE_LOSS_DATE_MISMATCH",
                "7:ROSTER_HIRE_DATE_MISMATCH");
        assertThat(issues).filteredOn(i -> Integer.valueOf(4).equals(i.get("rowNo")))
                .extracting(i -> i.get("suggestion")).containsExactly("2026-07-01");
        assertThat(issues).filteredOn(i -> Integer.valueOf(3).equals(i.get("rowNo")))
                .extracting(i -> (String) i.get("message")).singleElement().asString().contains("2개 취득일", "36일 늦습니다");
    }

    @Test
    void 사번이_직원명부에_있는지_이름이_같은지_본다() throws Exception {
        long migrationId = create("참조 테스트");
        upload(migrationId, "직원.csv", ROSTER, "HR_EMPLOYEE");
        long fileId = upload(migrationId, "근태기록.csv", ATTENDANCE, "REFERENCE");

        String body = mvc.perform(get(BASE + "/{id}/issues", migrationId).param("fileId", String.valueOf(fileId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> issues = JsonPath.read(body, "$.data.content");

        assertThat(issues).extracting(i -> i.get("rowNo") + ":" + i.get("code")).containsExactlyInAnyOrder(
                "4:EMP_NOT_IN_ROSTER", "5:EMP_KEY_MISSING", "6:EMP_NAME_MISMATCH", "null:EMPNO_ZERO_PADDED");
        assertThat(issues).filteredOn(i -> "EMP_KEY_MISSING".equals(i.get("code")))
                .extracting(i -> i.get("suggestion")).containsExactly("00125");
    }

    private long create(String name) throws Exception {
        String created = mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyId\":1,\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(created, "$.data.migrationId")).longValue();
    }

    private long upload(long migrationId, String name, String csv, String entity) throws Exception {
        String body = mvc.perform(multipart(BASE + "/{id}/files", migrationId)
                        .file(new MockMultipartFile("file", name, "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .param("entity", entity))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.data.fileId")).longValue();
    }
}
