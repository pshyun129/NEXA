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
import java.nio.charset.Charset;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 작업 생성 → 파일 업로드 → 작업 상세 → 파일 진단까지 실제 DB(Flyway 스키마)로 확인한다. */
class MigrationAdminIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/admin/migrations";

    @Autowired
    MockMvc mvc;

    private long createJob() throws Exception {
        String body = mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"companyId":1,"name":"2027년 1월 전환","cutoverDate":"2027-01-01"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(body, "$.data.migrationId");
        return id.longValue();
    }

    @Test
    void 엑셀_업로드부터_진단까지() throws Exception {
        long migrationId = createJob();
        MockMultipartFile xlsx = new MockMultipartFile("file", "직원명부.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", employeeXlsx());

        String uploaded = mvc.perform(multipart(BASE + "/{id}/files", migrationId).file(xlsx).param("entity", "HR_EMPLOYEE"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rows").value(2))
                .andExpect(jsonPath("$.data.headers").value(contains("사번", "이름", "입사일")))
                .andReturn().getResponse().getContentAsString();
        Number fileId = JsonPath.read(uploaded, "$.data.fileId");

        mvc.perform(get(BASE + "/{id}", migrationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("2027년 1월 전환"))
                .andExpect(jsonPath("$.data.files[0].fileName").value("직원명부.xlsx"))
                .andExpect(jsonPath("$.data.files[0].entity").value("HR_EMPLOYEE"));

        mvc.perform(get(BASE + "/{id}/files/{fileId}", migrationId, fileId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.columns[2].sourceColumn").value("입사일"))
                .andExpect(jsonPath("$.data.columns[2].detectedType").value("DATE"))
                .andExpect(jsonPath("$.data.columns[2].dateFormats").value(contains("YYYY-MM-DD", "YYYY.MM.DD")));
    }

    @Test
    void CP949_CSV를_읽는다() throws Exception {
        long migrationId = createJob();
        byte[] csv = "부서코드,부서명\nD100,개발1팀\n".getBytes(Charset.forName("MS949"));

        mvc.perform(multipart(BASE + "/{id}/files", migrationId)
                        .file(new MockMultipartFile("file", "조직.csv", "text/csv", csv)).param("entity", "HR_ORG"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.headers").value(contains("부서코드", "부서명")));
    }

    @Test
    void 깨진_파일은_400() throws Exception {
        long migrationId = createJob();

        mvc.perform(multipart(BASE + "/{id}/files", migrationId)
                        .file(new MockMultipartFile("file", "a.xlsx", "application/octet-stream", "abc".getBytes()))
                        .param("entity", "HR_ORG"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FILE_003"));
    }

    @Test
    void 없는_작업은_404() throws Exception {
        mvc.perform(get(BASE + "/{id}", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("MIG_001"));
    }

    private static byte[] employeeXlsx() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("직원 명부");
            Row h = sheet.createRow(1);
            h.createCell(0).setCellValue("사번");
            h.createCell(1).setCellValue("이름");
            h.createCell(2).setCellValue("입사일");
            Row r1 = sheet.createRow(2);
            r1.createCell(0).setCellValue("00123");
            r1.createCell(1).setCellValue("홍길동");
            r1.createCell(2).setCellValue("2024-01-02");
            Row r2 = sheet.createRow(3);
            r2.createCell(0).setCellValue("00124");
            r2.createCell(1).setCellValue("김민수");
            r2.createCell(2).setCellValue("2023.3.15");
            wb.write(out);
            return out.toByteArray();
        }
    }
}
