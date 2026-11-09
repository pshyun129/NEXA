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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 부양가족·원천징수 관련 검사 (소득세 기본공제·간이세액).
 * - 기본공제 요건: 기본공제 표시가 된 자녀는 20세 이하, 부모(직계존속)는 60세 이상 (소득세법 제50조, 장애인은 나이 제한 없음)
 * - 원천징수비율: 80·100·120% 중 하나 (소득세법 시행령 제194조)
 * - 공제가족수: 같은 작업의 다른 파일(급여대장)에 있는 공제가족수와 사번별로 대조. 급여대장 시트가 여럿이면 가장 늦은 달.
 *   이름에 연월이 없는 파일(부양가족 집계) 쪽에서만 본다. 월별 급여 시트마다 같은 경고가 반복되지 않게
 */
@Component
@RequiredArgsConstructor
public class DependentsChecker {

    // 열 이름은 ColumnMappingSuggester.normalize(소문자, 공백·괄호·밑줄 제거) 뒤에 통째로 비교
    private static final Pattern FAMILY_COUNT = Pattern.compile("(공제(대상)?)?(부양)?가족수(본인포함)?|taxfamily|familycnt|familycount");
    private static final Pattern RELATION = Pattern.compile("관계|가족관계|relation");
    private static final Pattern AGE = Pattern.compile("나이|연령|age");
    private static final Pattern BASIC_DEDUCTION = Pattern.compile("기본공제(여부|대상)?|basicded(uction)?");
    private static final Pattern RATIO = Pattern.compile("(원천)?징수비율%?|whtrate|withholdingratio");
    private static final Pattern YEAR_MONTH = Pattern.compile("(20\\d{2})\\D{0,3}(\\d{1,2})");

    private static final Set<String> YES = Set.of("y", "o", "○", "예", "yes", "true", "v", "✓", "1");
    private static final Set<String> CHILD = Set.of("자녀", "아들", "딸", "자", "녀", "손자", "손녀", "직계비속", "child", "son", "daughter");
    private static final Set<String> PARENT = Set.of("부", "모", "부친", "모친", "아버지", "어머니", "조부", "조모", "외조부", "외조모",
            "장인", "장모", "시부", "시모", "직계존속", "father", "mother", "parent", "grandfather", "grandmother");
    private static final Set<BigDecimal> RATIOS = Set.of(new BigDecimal("80"), new BigDecimal("100"), new BigDecimal("120"));

    private final MigrationFileRepository fileRepository;
    private final StagingRowRepository stagingRowRepository;

    public List<ValidationIssue> check(MigrationFile file, List<ParsedTable.Row> rows) {
        List<ValidationIssue> issues = new ArrayList<>();
        ageRule(file, rows, issues);
        ratio(file, rows, issues);
        familyCount(file, rows, issues);
        return issues;
    }

    /** 월별 급여 시트를 올리면 대조 결과가 바뀌는 같은 작업의 부양가족 파일들 */
    public List<MigrationFile> related(MigrationFile file) {
        if (column(file, FAMILY_COUNT) == null || yearMonth(file) == 0) {
            return List.of();
        }
        return fileRepository.findByMigrationIdOrderByFileIdAsc(file.getMigrationId()).stream()
                .filter(f -> !f.getFileName().equals(file.getFileName()) && column(f, FAMILY_COUNT) != null && yearMonth(f) == 0)
                .toList();
    }

    private static void ageRule(MigrationFile file, List<ParsedTable.Row> rows, List<ValidationIssue> issues) {
        String relation = column(file, RELATION);
        String age = column(file, AGE);
        String basic = column(file, BASIC_DEDUCTION);
        if (relation == null || age == null || basic == null) {
            return;
        }
        for (ParsedTable.Row row : rows) {
            String rel = lower(row.values().get(relation));
            Optional<BigDecimal> years = number(row.values().get(age));
            if (!YES.contains(lower(row.values().get(basic))) || years.isEmpty()) {
                continue;
            }
            int a = years.get().intValue();
            String rule = CHILD.contains(rel) && a > 20 ? "자녀(직계비속)는 20세 이하"
                    : PARENT.contains(rel) && a < 60 ? "부모(직계존속)는 60세 이상" : null;
            if (rule != null) {
                issues.add(new ValidationIssue(file.getFileId(), row.rowNo(), null, basic, Severity.WARN, IssueCode.DEPENDENT_AGE_RULE,
                        row.values().get(relation) + " 나이 " + a + "세인데 기본공제 대상으로 표시돼 있습니다. " + rule
                                + "만 기본공제 대상입니다 (장애인은 나이 제한 없음). 공제가족수와 소득세가 달라집니다",
                        row.values().get(basic), null));
            }
        }
    }

    private static void ratio(MigrationFile file, List<ParsedTable.Row> rows, List<ValidationIssue> issues) {
        String col = column(file, RATIO);
        if (col == null) {
            return;
        }
        for (ParsedTable.Row row : rows) {
            Optional<BigDecimal> v = number(row.values().get(col));
            if (v.isEmpty()) {
                continue;
            }
            BigDecimal pct = v.get().compareTo(new BigDecimal("2")) < 0 ? v.get().movePointRight(2) : v.get();   // 0.8·1·1.2 로 적은 회사
            if (RATIOS.stream().noneMatch(r -> r.compareTo(pct) == 0)) {
                issues.add(new ValidationIssue(file.getFileId(), row.rowNo(), null, col, Severity.WARN, IssueCode.WITHHOLDING_RATIO_INVALID,
                        "원천징수비율 " + row.values().get(col) + "은(는) 고를 수 있는 값(80·100·120%)이 아닙니다", row.values().get(col), "100"));
            }
        }
    }

    private void familyCount(MigrationFile file, List<ParsedTable.Row> rows, List<ValidationIssue> issues) {
        String mine = column(file, FAMILY_COUNT);
        String empCol = RosterReferenceChecker.findColumn(file, "empNo");
        if (mine == null || empCol == null || yearMonth(file) != 0) {
            return;
        }
        Optional<MigrationFile> payroll = fileRepository.findByMigrationIdOrderByFileIdAsc(file.getMigrationId()).stream()
                .filter(f -> !f.getFileName().equals(file.getFileName()) && column(f, FAMILY_COUNT) != null
                        && yearMonth(f) != 0 && RosterReferenceChecker.findColumn(f, "empNo") != null)
                .max(Comparator.comparing(DependentsChecker::yearMonth).thenComparing(MigrationFile::getFileId));
        if (payroll.isEmpty()) {
            return;
        }
        MigrationFile p = payroll.get();
        String pEmp = RosterReferenceChecker.findColumn(p, "empNo");
        String pFamily = column(p, FAMILY_COUNT);
        Map<String, String> theirs = new HashMap<>();
        for (ParsedTable.Row r : stagingRowRepository.findByFileId(p.getFileId())) {
            String emp = r.values().get(pEmp);
            if (emp != null && r.values().get(pFamily) != null) {
                theirs.put(key(emp), r.values().get(pFamily));
            }
        }
        String where = p.getFileName() + (p.getSheetName() == null ? "" : " " + p.getSheetName());
        for (ParsedTable.Row row : rows) {
            String emp = row.values().get(empCol);
            Optional<BigDecimal> a = number(row.values().get(mine));
            Optional<BigDecimal> b = emp == null ? Optional.empty() : number(theirs.get(key(emp)));
            if (a.isPresent() && b.isPresent() && a.get().compareTo(b.get()) != 0) {
                issues.add(new ValidationIssue(file.getFileId(), row.rowNo(), null, mine, Severity.WARN, IssueCode.FAMILY_COUNT_MISMATCH,
                        "공제가족수 " + plain(a.get()) + "명이 " + where + "의 '" + pFamily + "' " + plain(b.get())
                                + "명과 다릅니다. 부양가족 변동이 급여에 반영되지 않았거나 기본공제 대상 표시가 틀렸을 수 있습니다 (간이세액 소득세가 달라짐)",
                        plain(a.get()), plain(b.get())));
            }
        }
    }

    /** 시트·파일 이름의 연월 (2026-12 → 202612). 없으면 0 */
    static int yearMonth(MigrationFile f) {
        for (String s : new String[]{f.getSheetName(), f.getFileName()}) {
            var m = s == null ? null : YEAR_MONTH.matcher(s);
            if (m != null && m.find()) {
                return Integer.parseInt(m.group(1)) * 100 + Integer.parseInt(m.group(2));
            }
        }
        return 0;
    }

    private static String column(MigrationFile file, Pattern p) {
        return Arrays.stream(file.getHeaders()).filter(h -> p.matcher(ColumnMappingSuggester.normalize(h)).matches()).findFirst().orElse(null);
    }

    /** 숫자 셀로 저장돼 앞자리 0이 빠진 사번도 같게 본다 */
    private static String key(String empNo) {
        String s = empNo.strip();
        return s.matches("\\d+") ? s.replaceFirst("^0+(?=\\d)", "") : s;
    }

    private static String lower(String v) {
        return v == null ? "" : v.strip().toLowerCase();
    }

    private static Optional<BigDecimal> number(String v) {
        if (v == null) {
            return Optional.empty();
        }
        String s = v.replace(",", "").replace("%", "").strip();
        return s.matches("-?\\d+(\\.\\d+)?") ? Optional.of(new BigDecimal(s)) : Optional.empty();
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
