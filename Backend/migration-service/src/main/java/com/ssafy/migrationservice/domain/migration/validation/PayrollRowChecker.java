package com.ssafy.migrationservice.domain.migration.validation;

import com.ssafy.migrationservice.domain.migration.entity.MigrationFile;
import com.ssafy.migrationservice.domain.migration.mapping.ColumnMappingSuggester;
import com.ssafy.migrationservice.domain.migration.reader.ParsedTable;
import com.ssafy.migrationservice.domain.migration.validation.issue.IssueCode;
import com.ssafy.migrationservice.domain.migration.validation.issue.Severity;
import com.ssafy.migrationservice.domain.migration.validation.issue.ValidationIssue;
import com.ssafy.migrationservice.domain.migration.validation.payroll.PayrollColumns;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 급여 행 단위 검사.
 * - 실지급액이 음수인 행 (무급 휴직 달에 보험료·대출 상환을 그대로 공제 등)
 * - 한 파일에 여러 달이 세로로 쌓인 급여(귀속월 열)에서 사람마다 중간에 빠진 달 (다음 달에 소급 합산했을 수 있음)
 * 귀속월은 행의 귀속월 열, 없으면 시트·파일 이름(2026-12)에서 읽는다.
 */
public final class PayrollRowChecker {

    private static final Pattern PAY_MONTH_COLUMN = Pattern.compile("(귀속|지급|급여|대상)(연월|월)|paymonth|yearmonth");
    private static final Pattern YEAR_MONTH = Pattern.compile("(20\\d{2})\\D{0,3}(\\d{1,2})(?:\\D.*)?");

    private PayrollRowChecker() {
    }

    public static List<ValidationIssue> check(MigrationFile file, List<ParsedTable.Row> rows) {
        List<String> headers = List.of(file.getHeaders());
        List<ValidationIssue> issues = new ArrayList<>();
        int net = PayrollColumns.find(headers, PayrollColumns.NET);
        if (net >= 0) {
            String col = headers.get(net);
            for (ParsedTable.Row r : rows) {
                PayrollColumns.number(r.values().get(col)).filter(v -> v.signum() < 0).ifPresent(v -> issues.add(
                        new ValidationIssue(file.getFileId(), r.rowNo(), null, col, Severity.WARN, IssueCode.NEGATIVE_NET_PAY,
                                "'" + col + "'이(가) " + won(v) + "원으로 음수입니다. 무급 휴직·결근 달에 보험료나 대출 상환을 그대로 공제했는지 확인해 주세요",
                                r.values().get(col), null)));
            }
        }
        monthGaps(file, rows, issues);
        return issues;
    }

    /** 행의 귀속월 열 → 없으면 파일의 연월. 모르면 null */
    public static YearMonth payMonth(MigrationFile file, ParsedTable.Row row) {
        String col = payMonthColumn(file);
        YearMonth ym = col == null ? null : parse(row.values().get(col));
        if (ym == null) {
            int fileYm = DependentsChecker.yearMonth(file);
            ym = fileYm == 0 ? null : YearMonth.of(fileYm / 100, fileYm % 100);
        }
        return ym;
    }

    private static void monthGaps(MigrationFile file, List<ParsedTable.Row> rows, List<ValidationIssue> issues) {
        String monthCol = payMonthColumn(file);
        String empCol = RosterReferenceChecker.findColumn(file, "empNo");
        if (monthCol == null || empCol == null) {
            return;
        }
        Map<String, TreeMap<YearMonth, Integer>> byEmp = new LinkedHashMap<>();
        for (ParsedTable.Row r : rows) {
            String emp = r.values().get(empCol);
            YearMonth ym = parse(r.values().get(monthCol));
            if (emp != null && ym != null) {
                byEmp.computeIfAbsent(emp, k -> new TreeMap<>()).putIfAbsent(ym, r.rowNo());
            }
        }
        byEmp.forEach((emp, months) -> {
            YearMonth prev = null;
            for (Map.Entry<YearMonth, Integer> e : months.entrySet()) {
                if (prev != null && prev.plusMonths(1).isBefore(e.getKey())) {
                    String missing = prev.plusMonths(1) + (prev.plusMonths(2).isBefore(e.getKey()) ? " ~ " + e.getKey().minusMonths(1) : "");
                    issues.add(new ValidationIssue(file.getFileId(), e.getValue(), null, monthCol, Severity.WARN, IssueCode.PAY_MONTH_GAP,
                            "사번 " + emp + "의 " + missing + " 급여 행이 없습니다 (" + prev + " 다음이 " + e.getKey()
                                    + "). 다음 달에 소급해 합쳤다면 월별 이력·4대보험 귀속월이 틀어집니다", e.getKey().toString(), null));
                }
                prev = e.getKey();
            }
        });
    }

    private static String payMonthColumn(MigrationFile file) {
        return Arrays.stream(file.getHeaders()).filter(h -> PAY_MONTH_COLUMN.matcher(ColumnMappingSuggester.normalize(h)).matches())
                .findFirst().orElse(null);
    }

    static YearMonth parse(String value) {
        if (value == null) {
            return null;
        }
        Matcher m = YEAR_MONTH.matcher(value.strip());
        if (!m.matches()) {
            return null;
        }
        int month = Integer.parseInt(m.group(2));
        return month >= 1 && month <= 12 ? YearMonth.of(Integer.parseInt(m.group(1)), month) : null;
    }

    private static String won(BigDecimal v) {
        return new DecimalFormat("#,##0").format(v);
    }

    static Optional<BigDecimal> paid(MigrationFile file, ParsedTable.Row row) {
        List<String> headers = List.of(file.getHeaders());
        for (var p : List.of(PayrollColumns.GROSS, PayrollColumns.NET)) {
            int c = PayrollColumns.find(headers, p);
            if (c >= 0) {
                return PayrollColumns.number(row.values().get(headers.get(c)));
            }
        }
        return Optional.empty();
    }
}
