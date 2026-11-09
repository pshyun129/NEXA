package com.ssafy.migrationservice.domain.migration;

import com.jayway.jsonpath.JsonPath;
import com.ssafy.migrationservice.support.AbstractIntegrationTest;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 현장별 출역부(시트 여러 개, 출역일수 수식) ↔ 근로내용확인신고(성명·근로일수) */
class CrossFileTotalsIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/admin/migrations";

    // 근로자10은 신고에서 빠짐, 근로자2는 근로일수를 4로 잘못 적음, 근로자1은 두 현장을 합쳐 7일
    private static final String REPORT = """
            성명,근로일수,보수총액
            근로자1,7,1400000
            근로자2,4,1000000
            근로자3,5,1000000
            근로자4,5,1000000
            근로자5,5,1000000
            근로자6,5,1000000
            근로자7,5,1000000
            근로자8,5,1000000
            근로자9,5,1000000
            """;

    @Autowired
    MockMvc mvc;

    @Test
    void 출역부를_현장_시트를_합쳐_신고와_대조하고_빠진_사람과_같은_날_두_현장_출역을_찾는다() throws Exception {
        String created = mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("""
                        {"companyId":1,"name":"출역부 대조 테스트"}"""))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long migrationId = ((Number) JsonPath.read(created, "$.data.migrationId")).longValue();
        byte[] xlsx = dailyLabor();
        long garam = upload(migrationId, "출역부_2026-12.xlsx", xlsx, "가람");
        upload(migrationId, "출역부_2026-12.xlsx", xlsx, "나루");
        long report = upload(migrationId, "근로내용확인신고_2026-12.csv", REPORT.getBytes(StandardCharsets.UTF_8), null);

        assertThat(codes(migrationId, report)).contains("3:CROSS_FILE_MISMATCH", "null:MISSING_IN_COPY")
                .doesNotContain("2:CROSS_FILE_MISMATCH");
        assertThat(codes(migrationId, garam)).contains("4:SAME_DAY_TWO_SHEETS")
                .doesNotContain("5:SAME_DAY_TWO_SHEETS");
    }

    /** 가람: 근로자1~10이 1~5일 출역, 나루: 근로자1이 3일·20일 출역 (3일은 가람과 같은 날) */
    private static byte[] dailyLabor() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet garam = sheet(wb, "가람");
            for (int n = 1; n <= 10; n++) {
                worker(garam, n, Set.of(1, 2, 3, 4, 5));
            }
            Sheet naru = sheet(wb, "나루");
            worker(naru, 1, Set.of(3, 20));
            wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static Sheet sheet(XSSFWorkbook wb, String name) {
        Sheet s = wb.createSheet(name);
        s.createRow(0).createCell(0).setCellValue(name + " 2026년 12월 출역부");
        Row h = s.createRow(2);
        h.createCell(0).setCellValue("사번");
        h.createCell(1).setCellValue("성명");
        for (int d = 1; d <= 31; d++) {
            h.createCell(1 + d).setCellValue(d);
        }
        h.createCell(33).setCellValue("출역일수");
        return s;
    }

    /** 3행이 헤더라 근로자 n은 3 + n행 */
    private static void worker(Sheet s, int n, Set<Integer> days) {
        Row r = s.createRow(s.getLastRowNum() + 1);
        r.createCell(0).setCellValue("D26-" + (100 + n));
        r.createCell(1).setCellValue("근로자" + n);
        days.forEach(d -> r.createCell(1 + d).setCellValue(1));
        int excelRow = r.getRowNum() + 1;
        r.createCell(33).setCellFormula("COUNT(C" + excelRow + ":AG" + excelRow + ")");
    }

    private List<String> codes(long migrationId, long fileId) throws Exception {
        String body = mvc.perform(get(BASE + "/{id}/issues", migrationId).param("fileId", String.valueOf(fileId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> issues = JsonPath.read(body, "$.data.content");
        return issues.stream().map(i -> i.get("rowNo") + ":" + i.get("code")).toList();
    }

    private long upload(long migrationId, String name, byte[] content, String sheet) throws Exception {
        var req = multipart(BASE + "/{id}/files", migrationId)
                .file(new MockMultipartFile("file", name, "application/octet-stream", content))
                .param("entity", "REFERENCE");
        if (sheet != null) {
            req.param("sheet", sheet);
        }
        String body = mvc.perform(req).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.data.fileId")).longValue();
    }
}
