package com.ssafy.attendanceservice.domain.attendance.excel;

import com.ssafy.attendanceservice.domain.attendance.dto.AdminPeriodStatusResponse;
import com.ssafy.attendanceservice.domain.attendance.dto.AttendanceSummaryResponse;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AnomalyType;
import com.ssafy.attendanceservice.global.exception.BusinessException;
import com.ssafy.attendanceservice.global.exception.ErrorCode;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 관리자 기간별 근태 통계 → 엑셀(xlsx). 사원별 한 줄 + 마지막 줄 합계.
 * 시간은 시간 단위 숫자(소수 둘째 자리)로 넣어 엑셀에서 바로 계산할 수 있게 한다
 */
@Component
public class AttendanceExcelRenderer {

    private static final String SHEET_NAME = "근태 통계";
    private static final int COLUMN_WIDTH = 16 * 256;   // 글자 16개 너비 (POI 단위: 1/256 글자)

    // 엑셀에 넣는 근태이상 유형 (판정 보류 중인 휴게시간 부족·휴가 중 출근은 뺀다)
    private static final Map<AnomalyType, String> ANOMALY_COLUMNS = Map.of(
            AnomalyType.LATE, "지각",
            AnomalyType.EARLY_LEAVE, "조퇴",
            AnomalyType.MISSING_CLOCK, "출퇴근 누락",
            AnomalyType.UNDER_STD_HOURS, "소정근로 미달",
            AnomalyType.UNAPPROVED_OT, "미승인 초과근로");
    private static final List<AnomalyType> ANOMALY_ORDER = List.of(
            AnomalyType.LATE, AnomalyType.EARLY_LEAVE, AnomalyType.MISSING_CLOCK,
            AnomalyType.UNDER_STD_HOURS, AnomalyType.UNAPPROVED_OT);

    /** 한 줄에 들어갈 값 (사원 줄과 합계 줄이 같이 쓴다) */
    private record RowValues(String empId, String name, String deptName,
                             AttendanceSummaryResponse.Days days,
                             AttendanceSummaryResponse.WorkTime workTime,
                             AttendanceSummaryResponse.Leave leave,
                             Map<AnomalyType, Integer> anomalies) {
    }

    public byte[] render(AdminPeriodStatusResponse period) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(SHEET_NAME);
            CellStyle headerStyle = headerStyle(workbook);

            List<String> headers = headers();
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers.get(i));
                cell.setCellStyle(headerStyle);
            }

            int rowIndex = 1;
            for (AdminPeriodStatusResponse.Employee employee : period.employees()) {
                writeRow(sheet.createRow(rowIndex++), new RowValues(String.valueOf(employee.empId()), employee.name(),
                        employee.deptName(), employee.days(), employee.workTime(), employee.leave(),
                        employee.anomalies()), null);
            }
            AdminPeriodStatusResponse.Total total = period.total();
            writeRow(sheet.createRow(rowIndex), new RowValues("합계", total.employeeCount() + "명", "",
                    total.days(), total.workTime(), total.leave(), total.anomalies()), headerStyle);

            // 열 너비는 고정 (autoSizeColumn은 서버에 글꼴이 있어야 해서 도커 이미지에서 실패할 수 있음)
            for (int i = 0; i < headers.size(); i++) {
                sheet.setColumnWidth(i, COLUMN_WIDTH);
            }
            sheet.createFreezePane(3, 1);   // 사원 정보 열과 머리글 고정

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            throw new BusinessException(ErrorCode.EXCEL_EXPORT_FAILED, "근태 엑셀 생성 실패: " + e.getMessage());
        }
    }

    private static List<String> headers() {
        List<String> headers = new ArrayList<>(List.of(
                "사원 ID", "이름", "부서",
                "근무일수", "출근일수", "휴일 출근일수", "종일 휴가일수",
                "총 근로(시간)", "소정(시간)", "연장(시간)", "야간(시간)", "휴일(시간)",
                "승인 초과(시간)", "미승인 초과(시간)", "휴가(시간)", "무급휴가(시간)"));
        ANOMALY_ORDER.forEach(type -> headers.add(ANOMALY_COLUMNS.get(type) + "(회)"));
        return headers;
    }

    /** @param style 합계 줄처럼 강조할 때만, 아니면 null */
    private static void writeRow(Row row, RowValues values, CellStyle style) {
        List<Object> cells = new ArrayList<>(List.of(
                values.empId(), values.name(), values.deptName() == null ? "" : values.deptName(),
                values.days().workDays(), values.days().attendedDays(), values.days().holidayWorkedDays(),
                values.leave().leaveDays(),
                hours(values.workTime().totalMin()), hours(values.workTime().regularMin()),
                hours(values.workTime().otExtendedMin()),
                hours(values.workTime().nightMin() + values.workTime().otNightMin()),
                hours(values.workTime().otHolidayMin()),
                hours(values.workTime().approvedOtMin()), hours(values.workTime().unapprovedOtMin()),
                hours(values.leave().leaveMin()), hours(values.leave().unpaidLeaveMin())));
        ANOMALY_ORDER.forEach(type -> cells.add(values.anomalies().getOrDefault(type, 0)));

        for (int i = 0; i < cells.size(); i++) {
            Cell cell = row.createCell(i);
            Object value = cells.get(i);
            if (value instanceof Number number) {
                cell.setCellValue(number.doubleValue());
            } else {
                cell.setCellValue(String.valueOf(value));
            }
            if (style != null) {
                cell.setCellStyle(style);
            }
        }
    }

    /** 분 → 시간 (소수 둘째 자리 반올림) */
    private static double hours(int minutes) {
        return Math.round(minutes / 60.0 * 100) / 100.0;
    }

    private static CellStyle headerStyle(Workbook workbook) {
        Font bold = workbook.createFont();
        bold.setBold(true);
        CellStyle style = workbook.createCellStyle();
        style.setFont(bold);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }
}
