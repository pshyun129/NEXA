package com.ssafy.migrationservice.domain.migration.reader;

import com.ssafy.migrationservice.global.exception.BusinessException;
import com.ssafy.migrationservice.global.exception.ErrorCode;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TableReaderTest {

    private final TableReader reader = new TableReader();

    @Test
    void xlsx_제목행을_건너뛰고_날짜_숫자_수식_앞자리0을_보존한다() throws IOException {
        byte[] xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("2026년 직원 명부");          // 제목 행
            Row header = sheet.createRow(2);                                              // 1행은 빈 행
            header.createCell(0).setCellValue("사번");
            header.createCell(1).setCellValue("이름");
            header.createCell(2).setCellValue("입사일");
            header.createCell(3).setCellValue("기본급");
            header.createCell(4).setCellValue("연봉");

            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("yyyy.mm.dd"));
            Row r = sheet.createRow(3);
            r.createCell(0).setCellValue("00123");
            r.createCell(1).setCellValue("홍길동");
            r.createCell(2).setCellValue(LocalDate.of(2024, 1, 2));
            r.getCell(2).setCellStyle(dateStyle);
            r.createCell(3).setCellValue(3500000);
            r.createCell(4).setCellFormula("D4*12");
            wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
            sheet.createRow(4);                                                           // 빈 행
            wb.write(out);
            xlsx = out.toByteArray();
        }

        ParsedTable table = reader.read("직원명부.xlsx", xlsx);

        assertThat(table.headers()).containsExactly("사번", "이름", "입사일", "기본급", "연봉");
        assertThat(table.rows()).hasSize(1);
        ParsedTable.Row row = table.rows().get(0);
        assertThat(row.rowNo()).isEqualTo(4);
        assertThat(row.values()).containsEntry("사번", "00123")
                .containsEntry("입사일", "2024-01-02")
                .containsEntry("기본급", "3500000")
                .containsEntry("연봉", "42000000");
    }

    @Test
    void xlsx_모든_시트를_읽고_2줄_헤더_합계행_수식_오류값을_정리한다() throws IOException {
        byte[] xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet settings = wb.createSheet("설정");
            settings.createRow(0).createCell(0).setCellValue("항목");
            settings.getRow(0).createCell(1).setCellValue("값");
            settings.createRow(1).createCell(0).setCellValue("국민연금");
            settings.getRow(1).createCell(1).setCellValue(0.0475);

            Sheet s = wb.createSheet("2026-07");
            s.createRow(0).createCell(0).setCellValue("급여대장");
            Row info = s.createRow(1);                                                    // 제목 아래 '값 2칸 이상' 행
            info.createCell(0).setCellValue("귀속: 2026년 7월");
            info.createCell(2).setCellValue("기준일");
            info.createCell(3).setCellValue(LocalDate.of(2026, 7, 31));
            Row group = s.createRow(2);                                                   // 묶음 머리글 + 세로 병합
            group.createCell(0).setCellValue("사번");
            group.createCell(1).setCellValue("성명");
            group.createCell(2).setCellValue("07월");
            group.createCell(4).setCellValue("08월");
            s.addMergedRegion(new CellRangeAddress(2, 3, 0, 0));
            s.addMergedRegion(new CellRangeAddress(2, 3, 1, 1));
            s.addMergedRegion(new CellRangeAddress(2, 2, 2, 3));
            s.addMergedRegion(new CellRangeAddress(2, 2, 4, 5));
            Row header = s.createRow(3);
            String[] sub = {"연장", "야간", "연장", "야간"};
            for (int c = 0; c < sub.length; c++) {
                header.createCell(c + 2).setCellValue(sub[c]);
            }
            Object[][] data = {{"00101", "홍길동", 10, 2, 8, 1}, {"00102", "김민수", 5, 0, 4, 0}};
            for (int r = 0; r < data.length; r++) {
                Row row = s.createRow(4 + r);
                row.createCell(0).setCellValue((String) data[r][0]);
                row.createCell(1).setCellValue((String) data[r][1]);
                for (int c = 2; c < 6; c++) {
                    row.createCell(c).setCellValue(((Integer) data[r][c]).doubleValue());
                }
            }
            s.getRow(4).getCell(5).setCellFormula("D5-1");                                // 수식 셀
            s.getRow(5).getCell(3).setCellFormula("NA()");                                // 오류값 #N/A
            Row total = s.createRow(6);
            total.createCell(0).setCellValue("합 계");
            total.createCell(2).setCellFormula("SUM(C5:C6)");
            Row after = s.createRow(7);                                                   // 합계 아래에 추가한 행
            after.createCell(0).setCellValue("00103");
            after.createCell(1).setCellValue("이영희");
            after.createCell(2).setCellValue(3);
            wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
            wb.write(out);
            xlsx = out.toByteArray();
        }

        List<ParsedTable> tables = reader.readAll("급여대장.xlsx", xlsx);

        assertThat(tables).extracting(ParsedTable::sheetName).containsExactly("설정", "2026-07");
        ParsedTable t = tables.get(1);
        assertThat(t.headerRowNo()).isEqualTo(4);
        assertThat(t.headers()).containsExactly("사번", "성명", "07월 연장", "07월 야간", "08월 연장", "08월 야간");
        assertThat(t.rows()).extracting(ParsedTable.Row::rowNo).containsExactly(5, 6, 8);
        assertThat(t.totalRows()).containsExactly(7);
        assertThat(t.rowsAfterTotal()).containsExactly(8);
        assertThat(t.rows().get(0).values()).containsEntry("08월 야간", "1");
        assertThat(t.rows().get(0).formulas()).containsEntry("08월 야간", "D5-1");
        assertThat(t.rows().get(1).values()).containsEntry("07월 야간", null);
        assertThat(t.rows().get(1).errors()).containsEntry("07월 야간", "#N/A");
    }

    @Test
    void 헤더보다_글자칸이_조금_많은_데이터행이_있어도_헤더를_고른다() {
        byte[] csv = "사번,이름,부서\n00123,홍길동,개발팀\n00124,김민수,인사팀\n".getBytes(StandardCharsets.UTF_8);

        assertThat(reader.read("a.csv", csv).headers()).containsExactly("사번", "이름", "부서");
    }

    @Test
    void 이름이_붙은_소계와_위쪽_행을_더하는_수식만_있는_행도_소계로_뺀다() throws IOException {
        byte[] xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("12월");
            String[][] data = {{"사번", "점포", "기본급", "공제"}, {"00123", "강남점", "100", "10"}, {"00124", "강남점", "200", "20"},
                    {"", "강남점 소계", "", ""}, {"00125", "잠실점", "300", "30"}, {"", "", "", ""}, {"00126", "잠실점", "400", "40"}};
            for (int i = 0; i < data.length; i++) {
                Row r = s.createRow(i);
                for (int c = 0; c < data[i].length; c++) {
                    if (!data[i][c].isEmpty()) {
                        r.createCell(c).setCellValue(data[i][c]);
                    }
                }
            }
            s.getRow(3).createCell(2).setCellFormula("SUM(C2:C3)");      // 4행: 이름 붙은 소계
            s.getRow(3).createCell(3).setCellFormula("SUM(D2:D3)");
            s.getRow(5).createCell(2).setCellFormula("SUM(C5:C5)");      // 6행: 이름 없이 위쪽 행만 더함
            s.getRow(5).createCell(3).setCellFormula("SUM(D5:D5)");
            wb.write(out);
            xlsx = out.toByteArray();
        }

        ParsedTable t = reader.read("급여.xlsx", xlsx);

        assertThat(t.rows()).extracting(ParsedTable.Row::rowNo).containsExactly(2, 3, 5, 7);
        assertThat(t.totalRows()).containsExactly(4, 6);
        assertThat(t.rowsAfterTotal()).isEmpty();        // 소계는 총계가 아니므로 아래 행을 '합계 아래 행'으로 보지 않음
    }

    @Test
    void 위쪽_행을_세는_COUNTIF_행과_글자_한_칸뿐인_구역_제목_맺음말_행은_데이터에서_뺀다() throws IOException {
        byte[] xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("근무표");
            String[][] data = {{"사번", "성명", "직종", "1", "2"}, {"■ 1병동", "", "", "", ""}, {"001", "김간호", "간호사", "D", "N"},
                    {"002", "이간호", "간호사", "N", "D"}, {"", "D", "", "", ""}, {"", "N", "", "", ""}, {"", "위 내용이 맞음 (인)", "", "", ""}};
            for (int i = 0; i < data.length; i++) {
                Row r = s.createRow(i);
                for (int c = 0; c < data[i].length; c++) {
                    if (!data[i][c].isEmpty()) {
                        r.createCell(c).setCellValue(data[i][c]);
                    }
                }
            }
            for (int c = 3; c <= 4; c++) {      // 5·6행: 날짜별 근무 인원
                String col = String.valueOf((char) ('A' + c));
                s.getRow(4).createCell(c).setCellFormula("COUNTIF(" + col + "3:" + col + "4,\"D\")");
                s.getRow(5).createCell(c).setCellFormula("COUNTIF(" + col + "3:" + col + "4,\"N\")");
            }
            wb.write(out);
            xlsx = out.toByteArray();
        }

        ParsedTable t = reader.read("근무표.xlsx", xlsx);

        assertThat(t.rows()).extracting(ParsedTable.Row::rowNo).containsExactly(3, 4);
        assertThat(t.totalRows()).containsExactly(5, 6);
    }

    @Test
    void csv_UTF8_BOM을_읽는다() {
        byte[] body = "사번,이름\n00123,홍길동\n".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);

        ParsedTable table = reader.read("a.csv", withBom);

        assertThat(table.headers()).containsExactly("사번", "이름");
        assertThat(table.rows().get(0).values()).containsEntry("이름", "홍길동");
    }

    @Test
    void csv_CP949로_저장된_한글을_읽는다() {
        byte[] cp949 = "사번,이름\n00123,홍길동\n".getBytes(Charset.forName("MS949"));

        ParsedTable table = reader.read("a.csv", cp949);

        assertThat(table.headers()).containsExactly("사번", "이름");
        assertThat(table.rows().get(0).values()).containsEntry("이름", "홍길동");
    }

    @Test
    void 빈_헤더와_중복_헤더를_정리한다() {
        byte[] csv = "이름,,이름\n홍길동,x,길동\n".getBytes(StandardCharsets.UTF_8);

        ParsedTable table = reader.read("a.csv", csv);

        assertThat(table.headers()).containsExactly("이름", "컬럼2", "이름_2");
    }

    @Test
    void 헤더가_없으면_실패한다() {
        byte[] csv = "제목만 있음\n\n".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> reader.read("a.csv", csv))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FILE_NO_HEADER);
    }

    @Test
    void 깨진_xlsx는_읽기_실패로_처리한다() {
        assertThatThrownBy(() -> reader.read("a.xlsx", "abc".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FILE_READ_FAILED);
    }

    @Test
    void 지원하지_않는_확장자는_실패한다() {
        assertThatThrownBy(() -> reader.read("a.xls", new byte[]{1}))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FILE_TYPE_NOT_SUPPORTED);
    }
}
