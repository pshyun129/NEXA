package com.ssafy.migrationservice.domain.migration.load;

import com.ssafy.migrationservice.domain.migration.entity.MigrationFile;
import com.ssafy.migrationservice.domain.migration.load.HrTargetLoader.EmployeeRef;
import com.ssafy.migrationservice.domain.migration.reader.ParsedTable;
import com.ssafy.migrationservice.domain.migration.validation.PayGradeBaseChecker;
import com.ssafy.migrationservice.domain.migration.validation.PayrollRowChecker;
import com.ssafy.migrationservice.domain.migration.validation.payroll.PayrollColumns;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 지난 급여 파일(PAY_RESULT_HISTORY) → payroll 이관 행.
 * - payslips: 행마다 사원 ID·명세서용 사원 정보(user-service 에서 사번으로 찾음), 귀속연월, pay_item 코드별 금액, 합계.
 *   매핑되지 않은 지급·공제 열은 지급계·공제계와의 차이로 기타수당·기타공제가 된다 (항목 합 = 합계)
 * - grades(PAY_PROFILE): 직급별 기본급 = 가장 최근 달의 그 직급 사람들 기본급 중 가장 많은 값 (PayGradeBaseChecker 와 같은 규칙)
 */
final class PayrollRows {

    // 스키마 필드 → payroll pay_item.item_code
    static final Map<String, String> EARNINGS = linked(
            "basePay", "BASE_PAY", "positionAllowance", "POSITION_ALLOWANCE", "mealAllowance", "MEAL_ALLOWANCE",
            "otExtendedPay", "OT_EXTENDED_PAY", "otNightPay", "OT_NIGHT_PAY", "otHolidayPay", "OT_HOLIDAY_PAY",
            "annualLeavePay", "ANNUAL_LEAVE_PAY", "bonus", "BONUS");
    static final Map<String, String> DEDUCTIONS = linked(
            "incomeTax", "INCOME_TAX", "localIncomeTax", "LOCAL_INCOME_TAX", "nationalPension", "NATIONAL_PENSION",
            "healthInsurance", "HEALTH_INSURANCE", "longTermCare", "LONG_TERM_CARE", "employmentInsurance", "EMPLOYMENT_INSURANCE");

    /** 한 사람의 한 달. emp 는 미리보기에서 대상에 아직 없으면 null */
    record Line(int rowNo, String empNo, EmployeeRef emp, YearMonth month, Map<String, BigDecimal> amounts) {
    }

    private PayrollRows() {
    }

    /** strict 면 대상에 없는 사번·모르는 귀속월에서 실패 (적재), 아니면 비워 둔다 (미리보기) */
    static List<Line> lines(MigrationFile file, Map<String, String> fieldToColumn, List<ParsedTable.Row> rows,
                            Map<String, EmployeeRef> employees, boolean strict) {
        String empCol = fieldToColumn.get("empNo");
        List<Line> out = new ArrayList<>();
        for (ParsedTable.Row r : rows) {
            String empNo = empCol == null ? null : trim(r.values().get(empCol));
            EmployeeRef emp = empNo == null ? null : employees.get(empNo);
            YearMonth month = PayrollRowChecker.payMonth(file, r);
            if (strict && emp == null) {
                throw new IllegalStateException("행 " + r.rowNo() + ": 사번 '" + empNo + "'을(를) 인사 서비스에서 찾을 수 없습니다. 사원 파일을 먼저 적재해 주세요");
            }
            if (strict && month == null) {
                throw new IllegalStateException("행 " + r.rowNo() + ": 귀속월을 알 수 없습니다. 귀속월 열을 매핑하거나 시트 이름을 2026-11처럼 적어 주세요");
            }
            Map<String, BigDecimal> amounts = new LinkedHashMap<>();
            fieldToColumn.forEach((field, column) -> PayrollColumns.number(r.values().get(column))
                    .ifPresent(v -> amounts.put(field, v)));
            out.add(new Line(r.rowNo(), empNo, emp, month, amounts));
        }
        return out;
    }

    static List<LoadRow> payslips(List<Line> lines) {
        return lines.stream().map(l -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("empNo", l.empNo());
            d.put("empId", l.emp() == null ? null : l.emp().empId());
            d.put("payYear", l.month() == null ? null : l.month().getYear());
            d.put("payMonth", l.month() == null ? null : l.month().getMonthValue());
            d.put("empName", l.emp() == null ? null : l.emp().name());
            d.put("deptName", l.emp() == null ? null : l.emp().deptName());
            d.put("positionName", l.emp() == null ? null : l.emp().positionName());
            d.put("hireDate", l.emp() == null || l.emp().hireDate() == null ? null : l.emp().hireDate().toString());
            BigDecimal otHours = l.amounts().get("otExtendedHours");
            d.put("otExtendedMin", otHours == null ? 0 : won(otHours.multiply(BigDecimal.valueOf(60))));
            Map<String, Long> items = new LinkedHashMap<>();
            long earned = put(items, EARNINGS, l.amounts());
            long deducted = put(items, DEDUCTIONS, l.amounts());
            Long totalPay = amount(l.amounts(), "totalPay");
            Long totalDeduction = amount(l.amounts(), "totalDeduction");
            if (totalPay != null && totalPay != earned) {
                items.put("OTHER_EARN", totalPay - earned);
            }
            if (totalDeduction != null && totalDeduction != deducted) {
                items.put("OTHER_DEDUCTION", totalDeduction - deducted);
            }
            d.put("totalPay", totalPay);
            d.put("totalDeduction", totalDeduction);
            d.put("netPay", amount(l.amounts(), "netPay"));
            d.put("items", items);
            return new LoadRow(l.rowNo(), d);
        }).toList();
    }

    /** 직급 ID → 가장 최근 달 기본급의 최빈값. 행 번호는 이 목록의 순번 */
    static List<LoadRow> grades(List<Line> lines) {
        YearMonth latest = lines.stream().map(Line::month).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        Map<Long, List<BigDecimal>> byGrade = lines.stream()
                .filter(l -> l.emp() != null && l.emp().gradeId() != null && l.amounts().containsKey("basePay"))
                .filter(l -> latest == null || latest.equals(l.month()))
                .collect(Collectors.groupingBy(l -> l.emp().gradeId(), TreeMap::new,
                        Collectors.mapping(l -> l.amounts().get("basePay"), Collectors.toList())));
        List<LoadRow> out = new ArrayList<>();
        byGrade.forEach((gradeId, amounts) -> out.add(new LoadRow(out.size() + 1, linked(
                "gradeId", gradeId, "baseAmount", won(PayGradeBaseChecker.modeAmount(amounts).orElseThrow())))));
        return out;
    }

    private static long put(Map<String, Long> items, Map<String, String> codes, Map<String, BigDecimal> amounts) {
        long sum = 0;
        for (Map.Entry<String, String> e : codes.entrySet()) {
            Long v = amount(amounts, e.getKey());
            if (v != null) {
                items.put(e.getValue(), v);
                sum += v;
            }
        }
        return sum;
    }

    private static Long amount(Map<String, BigDecimal> amounts, String field) {
        BigDecimal v = amounts.get(field);
        return v == null ? null : won(v);
    }

    private static long won(BigDecimal v) {
        return v.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    @SuppressWarnings("unchecked")
    private static <V> Map<String, V> linked(Object... keyValues) {
        Map<String, V> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], (V) keyValues[i + 1]);
        }
        return m;
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
