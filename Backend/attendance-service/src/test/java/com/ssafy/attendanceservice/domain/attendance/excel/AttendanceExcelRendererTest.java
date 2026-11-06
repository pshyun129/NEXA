package com.ssafy.attendanceservice.domain.attendance.excel;

import com.ssafy.attendanceservice.domain.attendance.dto.AdminPeriodStatusResponse;
import com.ssafy.attendanceservice.domain.attendance.dto.AttendanceSummaryResponse;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AnomalyType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 기간별 통계 → 엑셀: 만든 파일을 다시 읽어 머리글, 사원 줄, 합계 줄을 확인 (Spring 없이) */
class AttendanceExcelRendererTest {

    private final AttendanceExcelRenderer renderer = new AttendanceExcelRenderer();

    private static AdminPeriodStatusResponse period() {
        var days = new AttendanceSummaryResponse.Days(21, 20, 1, 0);
        var workTime = new AttendanceSummaryResponse.WorkTime(9_690, 9_600, 0, 90, 0, 0, 60, 30);
        var leave = new AttendanceSummaryResponse.Leave(1, 480, 0);
        Map<AnomalyType, Integer> anomalies = Map.of(AnomalyType.LATE, 2, AnomalyType.UNAPPROVED_OT, 1);

        var employee = new AdminPeriodStatusResponse.Employee(101L, "이서연", 1L, "인사팀",
                days, workTime, leave, anomalies);
        var total = new AdminPeriodStatusResponse.Total(1, days, workTime, leave, anomalies);
        return new AdminPeriodStatusResponse(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                total, List.of(employee));
    }

    @Test
    void 사원_줄과_합계_줄이_있는_엑셀을_만듦() throws IOException {
        byte[] xlsx = renderer.render(period());

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("근태 통계");
            assertThat(sheet.getLastRowNum()).isEqualTo(2);     // 머리글, 사원 1명, 합계

            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("사원 ID");
            assertThat(header.getCell(16).getStringCellValue()).isEqualTo("지각(회)");

            Row employee = sheet.getRow(1);
            assertThat(employee.getCell(1).getStringCellValue()).isEqualTo("이서연");
            assertThat(employee.getCell(3).getNumericCellValue()).isEqualTo(21);    // 근무일수
            assertThat(employee.getCell(7).getNumericCellValue()).isEqualTo(161.5); // 9,690분 = 161.5시간
            assertThat(employee.getCell(16).getNumericCellValue()).isEqualTo(2);    // 지각

            Row total = sheet.getRow(2);
            assertThat(total.getCell(0).getStringCellValue()).isEqualTo("합계");
            assertThat(total.getCell(1).getStringCellValue()).isEqualTo("1명");
        }
    }

    @Test
    void 사원이_없어도_머리글과_합계만_있는_파일을_만듦() throws IOException {
        AdminPeriodStatusResponse base = period();
        AdminPeriodStatusResponse empty = new AdminPeriodStatusResponse(base.from(), base.to(),
                new AdminPeriodStatusResponse.Total(0, new AttendanceSummaryResponse.Days(0, 0, 0, 0),
                        new AttendanceSummaryResponse.WorkTime(0, 0, 0, 0, 0, 0, 0, 0),
                        new AttendanceSummaryResponse.Leave(0, 0, 0), Map.of()),
                List.of());

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(renderer.render(empty)))) {
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(1);
        }
    }
}
