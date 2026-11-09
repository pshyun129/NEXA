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

/** 조직 파일의 부서유형 단계·부서장 사번 ↔ 직원명부 (user-service 부서 유형·부서장 규칙) */
class DeptHeadIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/admin/migrations";

    // 4행: 명부에 없는 사번, 5행: 사번 대신 이름, 6행: 팀 아래 본부 + 퇴직자 + 다른 부서 소속, 7행: 회사 아래 회사 + 대표이사 없음
    // 8행: 비었고 그 팀 팀장이 한 명, 9행: 비었고 팀장이 두 명
    private static final String ORG = """
            부서코드,부서명,상위부서,부서유형,부서장
            C0,테스트(주),,회사,E001
            D1,개발본부,C0,본부,E002
            T1,개발1팀,D1,팀,E009
            T2,영업팀,D1,팀,김영업
            T3,품질팀,T1,본부,E004
            T4,디자인팀,C0,회사,
            T5,기획팀,D1,팀,
            T6,인사팀,D1,팀,
            """;

    private static final String ROSTER = """
            사번,이름,부서,직책,재직상태,퇴사일
            E001,대표,테스트(주),대표이사,재직,
            E002,본부장,개발1팀,본부장,재직,
            E003,김영업,영업팀,팀원,재직,
            E004,퇴사자,영업팀,팀장,TERMINATED,
            E005,기획장,기획팀,팀장,재직,
            E006,인사장1,인사팀,팀장,재직,
            E007,인사장2,인사팀,팀장,재직,
            """;

    @Autowired
    MockMvc mvc;

    @Test
    void 부서유형_단계와_부서장을_직원명부와_대조한다_명부를_나중에_올려도() throws Exception {
        long migrationId = create("부서장 테스트");
        long org = upload(migrationId, "조직.csv", ORG, "HR_ORG");

        assertThat(codes(migrationId, org)).contains("6:DEPT_TYPE_ORDER", "7:DEPT_TYPE_ORDER")
                .doesNotContain("4:HEAD_NOT_IN_ROSTER", "7:HEAD_VACANT");

        upload(migrationId, "직원명부.csv", ROSTER, "HR_EMPLOYEE");

        assertThat(codes(migrationId, org))
                .contains("4:HEAD_NOT_IN_ROSTER", "5:HEAD_NAME_GIVEN", "6:HEAD_NOT_EMPLOYED", "6:HEAD_OUTSIDE_DEPT",
                        "6:DEPT_TYPE_ORDER", "7:DEPT_TYPE_ORDER", "7:HEAD_VACANT", "8:HEAD_FROM_DUTY", "9:HEAD_VACANT")
                .doesNotContain("2:HEAD_OUTSIDE_DEPT", "3:HEAD_OUTSIDE_DEPT", "5:HEAD_OUTSIDE_DEPT",
                        "2:DEPT_TYPE_ORDER", "3:DEPT_TYPE_ORDER", "5:DEPT_TYPE_ORDER");
        assertThat(issues(migrationId, org)).filteredOn(i -> "HEAD_FROM_DUTY".equals(i.get("code")))
                .extracting(i -> i.get("suggestion")).containsExactly("E005");
    }

    @Test
    void 부서장_열이_없으면_파일_단위로_알리고_직책으로_정할_수_있는_부서_수를_센다() throws Exception {
        long migrationId = create("부서장 열 없음");
        long org = upload(migrationId, "조직.csv", """
                부서코드,부서명,상위부서
                C0,테스트(주),
                D1,개발본부,C0
                T5,기획팀,D1
                """, "HR_ORG");
        upload(migrationId, "직원명부.csv", ROSTER, "HR_EMPLOYEE");

        // 유형 열이 없으면 트리 깊이로: C0 대표이사 E001, T5 팀장 E005. D1 본부장 E002는 명부 소속이 이 파일에 없는 부서
        assertThat(issues(migrationId, org)).filteredOn(i -> "HEAD_COLUMN_MISSING".equals(i.get("code")))
                .singleElement().satisfies(i -> {
                    assertThat(i.get("rowNo")).isNull();
                    assertThat((String) i.get("message")).contains("3개 부서 중 2개");
                });
    }

    private long create(String name) throws Exception {
        String created = mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("""
                        {"companyId":1,"name":"%s"}""".formatted(name)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(created, "$.data.migrationId")).longValue();
    }

    private List<String> codes(long migrationId, long fileId) throws Exception {
        return issues(migrationId, fileId).stream().map(i -> i.get("rowNo") + ":" + i.get("code")).toList();
    }

    private List<Map<String, Object>> issues(long migrationId, long fileId) throws Exception {
        String body = mvc.perform(get(BASE + "/{id}/issues", migrationId).param("fileId", String.valueOf(fileId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.content");
    }

    private long upload(long migrationId, String name, String csv, String entity) throws Exception {
        String body = mvc.perform(multipart(BASE + "/{id}/files", migrationId)
                        .file(new MockMultipartFile("file", name, "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .param("entity", entity))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.data.fileId")).longValue();
    }
}
