package com.ssafy.migrationservice.domain.migration.validation;

import com.ssafy.migrationservice.domain.migration.entity.MigrationFile;
import com.ssafy.migrationservice.domain.migration.mapping.ColumnMappingSuggester;
import com.ssafy.migrationservice.domain.migration.reader.ParsedTable;
import com.ssafy.migrationservice.domain.migration.repository.MigrationFileRepository;
import com.ssafy.migrationservice.domain.migration.repository.StagingRowRepository;
import com.ssafy.migrationservice.domain.migration.validation.issue.IssueCode;
import com.ssafy.migrationservice.domain.migration.validation.issue.Severity;
import com.ssafy.migrationservice.domain.migration.validation.issue.ValidationIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 연봉이력 파일 검사와 파일 사이 대조.
 * - 연봉이력: 사람·연도별 행(연도 열 + 연봉 열) 또는 사람 1행에 연도별 열(SAL_2025, 연봉(2026) …)
 * - 인상률을 3.5%가 아니라 3.5로 적은 칸 (같은 열 대부분이 1보다 작은데 이 값만 1 이상)
 * - 월 기본급(없으면 연봉 ÷ 12) ↔ 같은 작업의 가장 늦은 달 급여 시트 기본급 (사번별, 급여 시트의 연도)
 * - 다른 파일의 연도 붙은 연봉 열(4대보험 '연봉(2026)') ↔ 연봉이력 그 해 연봉
 */
@Component
@RequiredArgsConstructor
public class SalaryHistoryChecker {

    // ColumnMappingSuggester.normalize(소문자, 공백·괄호·밑줄 제거) 뒤에 비교
    private static final Pattern YEAR_COLUMN = Pattern.compile("(적용)?(연도|년도)|year|applyyear");
    private static final Pattern ANNUAL = Pattern.compile("연봉(액)?|annual(salary)?|salary");
    private static final Pattern ANNUAL_TAGGED = Pattern.compile("(?:연봉|sal|salary|annual)\\D{0,3}(20\\d{2})");
    private static final Pattern MONTHLY = Pattern.compile("월기본급|월급여(기본)?|monthlybase|monthlysalary");
    private static final Pattern MONTHLY_TAGGED = Pattern.compile("(?:월기본급|월급여|monthlybase|monthlysalary)\\D{0,3}(20\\d{2})");
    private static final Pattern RAISE = Pattern.compile("(연봉)?인상률\\D{0,3}(20\\d{2})?|raise(rate)?\\D{0,3}(20\\d{2})?");
    private static final Pattern BASE_PAY = Pattern.compile("기본급|basepay|basesalary");
    private static final BigDecimal MONTHLY_TOLERANCE = new BigDecimal("100");     // 회사마다 다른 끝수 처리(10원·100원)
    private static final BigDecimal DIVIDED_TOLERANCE = new BigDecimal("1000");    // 연봉 ÷ 12로 비교할 때

    private final MigrationFileRepository fileRepository;
    private final StagingRowRepository stagingRowRepository;

    /** 사번 → 연도 → 값이 있는 칸 */
    private record Cell(int rowNo, String column, BigDecimal value) {
    }

    private record History(Map<String, Map<Integer, Cell>> annual, Map<String, Map<Integer, Cell>> monthly) {
        boolean isHistory() {
            return annual.values().stream().anyMatch(y -> y.size() >= 2);
        }
    }

    public List<ValidationIssue> check(MigrationFile file, List<ParsedTable.Row> rows) {
        String empCol = RosterReferenceChecker.findColumn(file, "empNo");
        if (empCol == null) {
            return List.of();
        }
        List<ValidationIssue> issues = new ArrayList<>();
        History mine = read(file, rows, empCol);
        if (mine.isHistory()) {
            raiseAsPercent(file, rows, issues);
            againstPayroll(file, mine, issues);
        } else if (!mine.annual().isEmpty()) {
            againstHistory(file, mine, issues);
        }
        return issues;
    }

    /** 이 파일을 올리면 대조 결과가 바뀌는 같은 작업의 파일들 (급여 시트 → 연봉이력, 연봉이력 → 연도 붙은 연봉 열이 있는 파일) */
    public List<MigrationFile> related(MigrationFile file) {
        boolean payroll = DependentsChecker.yearMonth(file) != 0 && column(file, BASE_PAY) != null;
        boolean history = isHistoryShape(file);
        if (!payroll && !history) {
            return List.of();
        }
        return fileRepository.findByMigrationIdOrderByFileIdAsc(file.getMigrationId()).stream()
                .filter(f -> !f.getFileName().equals(file.getFileName()))
                .filter(f -> payroll ? isHistoryShape(f) : tagged(f, ANNUAL_TAGGED).size() == 1)
                .toList();
    }

    // ---------- 읽기 ----------

    private History read(MigrationFile file, List<ParsedTable.Row> rows, String empCol) {
        Map<String, Map<Integer, Cell>> annual = new HashMap<>();
        Map<String, Map<Integer, Cell>> monthly = new HashMap<>();
        String yearCol = column(file, YEAR_COLUMN);
        String annualCol = column(file, ANNUAL);
        String monthlyCol = column(file, MONTHLY);
        Map<String, Integer> annualTagged = tagged(file, ANNUAL_TAGGED);
        Map<String, Integer> monthlyTagged = tagged(file, MONTHLY_TAGGED);
        for (ParsedTable.Row r : rows) {
            String emp = r.values().get(empCol);
            if (emp == null) {
                continue;
            }
            String key = key(emp);
            Optional<BigDecimal> year = yearCol == null ? Optional.empty() : number(r.values().get(yearCol));
            if (year.isPresent() && annualCol != null) {      // 사람·연도별 행
                int y = year.get().intValue();
                put(annual, key, y, r, annualCol);
                if (monthlyCol != null) {
                    put(monthly, key, y, r, monthlyCol);
                }
            }
            annualTagged.forEach((col, y) -> put(annual, key, y, r, col));      // 연도별 열
            monthlyTagged.forEach((col, y) -> put(monthly, key, y, r, col));
        }
        return new History(annual, monthly);
    }

    private static void put(Map<String, Map<Integer, Cell>> target, String emp, int year, ParsedTable.Row r, String col) {
        String raw = r.values().get(col);
        BigDecimal v = raw == null ? BigDecimal.ZERO : number(raw).orElse(null);    // 빈 칸은 0 (연봉이 빠진 행을 잡으려고)
        if (v != null) {
            target.computeIfAbsent(emp, k -> new HashMap<>()).put(year, new Cell(r.rowNo(), col, v));
        }
    }

    private boolean isHistoryShape(MigrationFile f) {
        return (column(f, YEAR_COLUMN) != null && column(f, ANNUAL) != null) || tagged(f, ANNUAL_TAGGED).size() >= 2;
    }

    // ---------- 검사 ----------

    private static void raiseAsPercent(MigrationFile file, List<ParsedTable.Row> rows, List<ValidationIssue> issues) {
        List<String> cols = Arrays.stream(file.getHeaders()).filter(h -> RAISE.matcher(ColumnMappingSuggester.normalize(h)).matches()).toList();
        List<BigDecimal> all = new ArrayList<>();
        rows.forEach(r -> cols.forEach(c -> number(r.values().get(c)).ifPresent(all::add)));
        long fractions = all.stream().filter(v -> v.abs().compareTo(BigDecimal.ONE) < 0).count();
        if (all.isEmpty() || fractions < all.size() * 0.8) {
            return;     // 이 회사는 원래 3.5처럼 % 숫자로 적는다
        }
        for (ParsedTable.Row r : rows) {
            for (String c : cols) {
                number(r.values().get(c)).filter(v -> v.abs().compareTo(BigDecimal.ONE) >= 0).ifPresent(v -> issues.add(
                        new ValidationIssue(file.getFileId(), r.rowNo(), null, c, Severity.WARN, IssueCode.RAISE_RATE_AS_PERCENT,
                                "인상률 " + r.values().get(c) + "은(는) 다른 행처럼 비율(0.035)이 아니라 %(3.5)로 적힌 것 같습니다. 이 값으로 계산한 연봉이 "
                                        + v.add(BigDecimal.ONE).stripTrailingZeros().toPlainString() + "배가 됩니다",
                                r.values().get(c), v.movePointLeft(2).stripTrailingZeros().toPlainString())));
            }
        }
    }

    private void againstPayroll(MigrationFile file, History mine, List<ValidationIssue> issues) {
        Optional<MigrationFile> payroll = fileRepository.findByMigrationIdOrderByFileIdAsc(file.getMigrationId()).stream()
                .filter(f -> !f.getFileName().equals(file.getFileName()) && DependentsChecker.yearMonth(f) != 0
                        && column(f, BASE_PAY) != null && RosterReferenceChecker.findColumn(f, "empNo") != null)
                .max(Comparator.comparing(DependentsChecker::yearMonth).thenComparing(MigrationFile::getFileId));
        if (payroll.isEmpty()) {
            return;
        }
        MigrationFile p = payroll.get();
        int year = DependentsChecker.yearMonth(p) / 100;
        String pEmp = RosterReferenceChecker.findColumn(p, "empNo");
        String pBase = column(p, BASE_PAY);
        String where = p.getFileName() + (p.getSheetName() == null ? "" : " " + p.getSheetName());
        for (ParsedTable.Row r : stagingRowRepository.findByFileId(p.getFileId())) {
            String emp = r.values().get(pEmp);
            Optional<BigDecimal> base = number(r.values().get(pBase));
            if (emp == null || base.isEmpty()) {
                continue;
            }
            Cell m = mine.monthly().getOrDefault(key(emp), Map.of()).get(year);
            Cell a = mine.annual().getOrDefault(key(emp), Map.of()).get(year);
            if (m != null && m.value().subtract(base.get()).abs().compareTo(MONTHLY_TOLERANCE) > 0) {
                issues.add(new ValidationIssue(file.getFileId(), m.rowNo(), null, m.column(), Severity.WARN, IssueCode.SALARY_PAYROLL_MISMATCH,
                        year + "년 월 기본급 " + won(m.value()) + "원이 " + where + "의 '" + pBase + "' " + won(base.get())
                                + "원과 다릅니다. 연봉 갱신이 빠졌거나 연봉이 잘못 입력됐을 수 있습니다", m.value().toPlainString(), base.get().toPlainString()));
            } else if (m == null && a != null) {
                BigDecimal divided = a.value().divide(new BigDecimal("12"), 0, RoundingMode.HALF_UP);
                if (divided.subtract(base.get()).abs().compareTo(DIVIDED_TOLERANCE) > 0) {
                    issues.add(new ValidationIssue(file.getFileId(), a.rowNo(), null, a.column(), Severity.WARN, IssueCode.SALARY_PAYROLL_MISMATCH,
                            year + "년 연봉 " + won(a.value()) + "원 ÷ 12 = " + won(divided) + "원이 " + where + "의 '" + pBase + "' " + won(base.get())
                                    + "원과 다릅니다. 연봉에 수당이 포함된 회사면 정상일 수 있습니다", a.value().toPlainString(), null));
                }
            }
        }
    }

    private void againstHistory(MigrationFile file, History mine, List<ValidationIssue> issues) {
        Optional<MigrationFile> historyFile = fileRepository.findByMigrationIdOrderByFileIdAsc(file.getMigrationId()).stream()
                .filter(f -> !f.getFileName().equals(file.getFileName()) && isHistoryShape(f) && RosterReferenceChecker.findColumn(f, "empNo") != null)
                .max(Comparator.comparing(MigrationFile::getFileId));
        if (historyFile.isEmpty()) {
            return;
        }
        MigrationFile h = historyFile.get();
        History theirs = read(h, stagingRowRepository.findByFileId(h.getFileId()), RosterReferenceChecker.findColumn(h, "empNo"));
        String where = h.getFileName() + (h.getSheetName() == null ? "" : " " + h.getSheetName());
        mine.annual().forEach((emp, byYear) -> byYear.forEach((year, cell) -> {
            Cell other = theirs.annual().getOrDefault(emp, Map.of()).get(year);
            if (other != null && other.value().signum() > 0 && cell.value().compareTo(other.value()) != 0) {
                issues.add(new ValidationIssue(file.getFileId(), cell.rowNo(), null, cell.column(), Severity.WARN, IssueCode.SALARY_HISTORY_MISMATCH,
                        "'" + cell.column() + "' " + won(cell.value()) + "원이 " + where + "의 " + year + "년 연봉 " + won(other.value())
                                + "원과 다릅니다. 어느 쪽이 최신인지 확인해 주세요 (보수월액·보험료가 달라집니다)", cell.value().toPlainString(), other.value().toPlainString()));
            }
        }));
    }

    // ---------- 공통 ----------

    private static String column(MigrationFile file, Pattern p) {
        return Arrays.stream(file.getHeaders()).filter(h -> p.matcher(ColumnMappingSuggester.normalize(h)).matches()).findFirst().orElse(null);
    }

    /** 이름에 연도가 붙은 열 → 연도 */
    private static Map<String, Integer> tagged(MigrationFile file, Pattern p) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String h : file.getHeaders()) {
            Matcher m = p.matcher(ColumnMappingSuggester.normalize(h));
            if (m.matches()) {
                out.put(h, Integer.parseInt(m.group(1)));
            }
        }
        return out;
    }

    private static String key(String empNo) {
        String s = empNo.strip();
        return s.matches("\\d+") ? s.replaceFirst("^0+(?=\\d)", "") : s;
    }

    private static Optional<BigDecimal> number(String v) {
        if (v == null) {
            return Optional.empty();
        }
        String s = v.replace(",", "").replace("원", "").strip();
        return s.matches("-?\\d+(\\.\\d+)?") ? Optional.of(new BigDecimal(s)) : Optional.empty();
    }

    private static String won(BigDecimal v) {
        return new DecimalFormat("#,##0").format(v);
    }
}
