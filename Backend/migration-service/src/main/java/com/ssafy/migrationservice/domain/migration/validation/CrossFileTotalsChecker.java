package com.ssafy.migrationservice.domain.migration.validation;

import com.ssafy.migrationservice.domain.migration.entity.MigrationFile;
import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationEntity;
import com.ssafy.migrationservice.domain.migration.mapping.ColumnMappingSuggester;
import com.ssafy.migrationservice.domain.migration.reader.ParsedTable;
import com.ssafy.migrationservice.domain.migration.repository.MigrationFileRepository;
import com.ssafy.migrationservice.domain.migration.repository.StagingRowRepository;
import com.ssafy.migrationservice.domain.migration.validation.formula.Countif;
import com.ssafy.migrationservice.domain.migration.validation.issue.IssueCode;
import com.ssafy.migrationservice.domain.migration.validation.issue.Severity;
import com.ssafy.migrationservice.domain.migration.validation.issue.ValidationIssue;
import com.ssafy.migrationservice.domain.migration.validation.payroll.PayrollColumns;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 파일 사이 집계 대조. 근무표·근태 집계·출역부·시수표처럼 사람마다 횟수·시간·일수를 센 파일과
 * 그 값을 옮겨 적은 급여대장·신고 파일을 사람(사번, 한쪽에 사번이 없으면 이름)·귀속월로 맞춰 비교한다.
 * - 열은 흔한 이름으로만 묶는다 (나이트횟수 ↔ N, 출역일수 ↔ 근로일수, 강의시수 ↔ 12월 시수 …)
 * - 한 파일의 시트들은 합쳐서 본다 (현장별 출역부 시트를 더한 출역일수 = 신고 근로일수)
 * - 수식으로 센 쪽을 원본, 값만 있는 쪽을 옮겨 적은 쪽으로 보고 옮겨 적은 쪽에 경고한다 (둘 다 값이면 둘 다).
 *   원본이 같은 행 COUNTIF면 범위를 다시 센 값과 비교한다 (원본 집계가 틀린 것을 그대로 옮겼는지까지)
 * - 원본에 값이 있는데 옮겨 적은 파일에 없는 사람: 두 파일 사람이 거의 같을 때(90% 이상)만 알린다 (신고 누락)
 * - 같은 파일의 다른 시트에 같은 사람이 같은 날 출역·근무한 기록 (현장 이중 출역, 지원 근무 이중 기록)
 */
@Component
@RequiredArgsConstructor
public class CrossFileTotalsChecker {

    enum Measure {
        NIGHT_SHIFTS("나이트(횟수|수)?|야간(근무)?(횟수|일수)|n"),
        NIGHT_HOURS("야간(근로)?(시간|h)"),
        OVERTIME_HOURS("연장(근로)?(시간|h)"),
        HOLIDAY_HOURS("휴일(근로)?(시간|h)"),
        WORK_DAYS("(근무|근로|출역|출근)일수"),
        LECTURE_HOURS("(강의)?시수|\\d{1,2}월시수");

        private final Pattern pattern;

        Measure(String regex) {
            this.pattern = Pattern.compile(regex);
        }
    }

    private static final Pattern NAME_EXTRA = Pattern.compile("강사(명)?|근로자(명)?");
    private static final double SAME_PEOPLE = 0.9;
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private final MigrationFileRepository fileRepository;
    private final StagingRowRepository stagingRowRepository;

    /** 행 하나: 사번·이름(정규화)·귀속월과 집계 항목 값 */
    private record Entry(MigrationFile file, int rowNo, String emp, String name, YearMonth month, Map<Measure, BigDecimal> values) {
    }

    /**
     * 파일 하나(시트 여러 개)
     * @param counted 수식으로 센 항목 (원본 쪽)
     * @param people  값이 없는 행까지 포함한 사번·이름 (빠진 사람을 찾을 때. 시수 칸이 빈 고정급 강사도 '있음')
     */
    private record Side(String fileName, List<Entry> entries, Map<Measure, String> headers, Set<Measure> counted,
                        boolean hasEmp, boolean hasName, Set<String> people) {
    }

    private record Person(String key, YearMonth month) {
    }

    public List<ValidationIssue> check(MigrationFile file, List<ParsedTable.Row> rows) {
        if (file.getEntity() == MigrationEntity.HR_EMPLOYEE || file.getEntity() == MigrationEntity.HR_ORG) {
            return List.of();
        }
        List<MigrationFile> all = fileRepository.findByMigrationIdOrderByFileIdAsc(file.getMigrationId());
        List<ValidationIssue> issues = new ArrayList<>();
        sameDayTwoSheets(file, rows, all, issues);
        if (measures(file).isEmpty() || keyColumn(file) == null) {
            return issues;
        }
        Side mine = side(file.getFileName(), all, file, rows);
        Map<String, List<MigrationFile>> others = all.stream()
                .filter(f -> !f.getFileName().equals(file.getFileName()) && f.getEntity() != MigrationEntity.HR_EMPLOYEE
                        && f.getEntity() != MigrationEntity.HR_ORG && !measures(f).isEmpty() && keyColumn(f) != null)
                .collect(Collectors.groupingBy(MigrationFile::getFileName, LinkedHashMap::new, Collectors.toList()));
        for (String other : others.keySet()) {
            compare(file, mine, side(other, all, null, null), issues);
        }
        return issues;
    }

    /** 이 파일을 올리면 대조 결과가 바뀌는 같은 작업의 파일들 */
    public List<MigrationFile> related(MigrationFile file) {
        boolean totals = !measures(file).isEmpty() && keyColumn(file) != null;
        boolean grid = !DayGrid.columns(List.of(file.getHeaders())).isEmpty();
        return fileRepository.findByMigrationIdOrderByFileIdAsc(file.getMigrationId()).stream()
                .filter(f -> !f.getFileId().equals(file.getFileId()))
                .filter(f -> f.getFileName().equals(file.getFileName())
                        ? grid && !DayGrid.columns(List.of(f.getHeaders())).isEmpty()
                        : totals && !measures(f).isEmpty() && keyColumn(f) != null)
                .toList();
    }

    // ---------- 집계 대조 ----------

    private void compare(MigrationFile file, Side mine, Side theirs, List<ValidationIssue> issues) {
        boolean byEmp = mine.hasEmp() && theirs.hasEmp();
        if (!byEmp && !(mine.hasName() && theirs.hasName())) {
            return;
        }
        Set<String> ambiguous = byEmp ? Set.of() : ambiguousNames(mine, theirs);
        Map<String, Missing> missing = new LinkedHashMap<>();
        for (Measure m : mine.headers().keySet()) {
            if (!theirs.headers().containsKey(m) || mine.counted().contains(m)) {
                continue;   // 이 파일이 원본(수식으로 셈)이면 옮겨 적은 쪽이 알린다
            }
            Map<Person, BigDecimal> my = totals(mine, m, byEmp, ambiguous);
            Map<Person, BigDecimal> their = totals(theirs, m, byEmp, ambiguous);
            Map<Person, Entry> firstRow = firstRows(mine, file, m, byEmp, ambiguous);
            for (Map.Entry<Person, Entry> e : firstRow.entrySet()) {
                Optional<BigDecimal> other = match(their, e.getKey());
                BigDecimal value = my.get(e.getKey());
                if (other.isEmpty() || value.subtract(other.get()).abs().compareTo(TOLERANCE) < 0) {
                    continue;
                }
                boolean source = theirs.counted().contains(m);
                Person p = e.getKey();
                issues.add(new ValidationIssue(file.getFileId(), e.getValue().rowNo(), null, mine.headers().get(m), Severity.WARN,
                        IssueCode.CROSS_FILE_MISMATCH,
                        "'" + mine.headers().get(m) + "' " + plain(value) + "이(가) " + theirs.fileName() + "의 '" + theirs.headers().get(m) + "' "
                                + plain(other.get()) + (source ? "(수식으로 센 원본 값)" : "") + "과(와) 다릅니다 (" + who(byEmp, p) + ")"
                                + (source ? ". 옮겨 적다 틀렸거나 원본 집계 뒤에 바뀌었을 수 있습니다" : ". 어느 쪽이 맞는지 확인해 주세요"),
                        value.toPlainString(), other.get().toPlainString()));
            }
            if (theirs.counted().contains(m)) {
                missing(mine, theirs, m, byEmp, ambiguous, their).forEach(missing::putIfAbsent);
            }
        }
        // 빠진 사람은 파일(시트 여러 개) 단위라 첫 시트에만 남긴다
        boolean sameFirst = mine.entries().stream().map(e -> e.file().getFileId()).min(Long::compare).map(id -> id.equals(file.getFileId())).orElse(true);
        if (sameFirst) {
            missing.forEach((key, x) -> issues.add(new ValidationIssue(file.getFileId(), null, null, mine.headers().get(x.measure()), Severity.WARN,
                    IssueCode.MISSING_IN_COPY,
                    theirs.fileName() + where(x.entry()) + "의 " + (byEmp ? "사번 " + key : "'" + key + "'") + "은(는) '" + theirs.headers().get(x.measure())
                            + "' " + plain(x.value()) + "인데 이 파일에 없습니다. 신고·지급에서 빠졌을 수 있습니다", key, null)));
        }
    }

    private record Missing(Measure measure, Entry entry, BigDecimal value) {
    }

    /** 원본에 값이 있는데 이 파일에 없는 사람. 두 파일 사람이 거의 같을 때만 */
    private static Map<String, Missing> missing(Side mine, Side theirs, Measure m, boolean byEmp, Set<String> ambiguous,
                                                Map<Person, BigDecimal> their) {
        Set<String> present = mine.people();
        Set<YearMonth> months = mine.entries().stream().map(Entry::month).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, Missing> candidates = new TreeMap<>();
        Set<String> people = new HashSet<>();
        for (Entry e : theirs.entries()) {
            String k = key(e, byEmp);
            BigDecimal v = their.get(new Person(k, e.month()));
            if (k == null || ambiguous.contains(k) || v == null || v.signum() <= 0
                    || (e.month() != null && !months.isEmpty() && !months.contains(e.month()))) {
                continue;
            }
            people.add(k);
            if (!present.contains(k)) {
                candidates.putIfAbsent(k, new Missing(m, e, v));
            }
        }
        boolean samePeople = !people.isEmpty() && (people.size() - candidates.size()) >= people.size() * SAME_PEOPLE;
        return samePeople ? candidates : Map.of();
    }

    /** 이 사람·달의 상대 값: 같은 달, 상대가 달을 모르면 그 값, 내가 달을 모르면 상대에게 한 달만 있을 때 그 값 */
    private static Optional<BigDecimal> match(Map<Person, BigDecimal> their, Person p) {
        if (their.containsKey(p)) {
            return Optional.of(their.get(p));
        }
        if (p.month() != null && their.containsKey(new Person(p.key(), null))) {
            return Optional.of(their.get(new Person(p.key(), null)));
        }
        if (p.month() == null) {
            List<BigDecimal> any = their.entrySet().stream().filter(e -> e.getKey().key().equals(p.key())).map(Map.Entry::getValue).toList();
            return any.size() == 1 ? Optional.of(any.get(0)) : Optional.empty();
        }
        return Optional.empty();
    }

    private static Map<Person, BigDecimal> totals(Side side, Measure m, boolean byEmp, Set<String> ambiguous) {
        Map<Person, BigDecimal> out = new HashMap<>();
        for (Entry e : side.entries()) {
            String k = key(e, byEmp);
            BigDecimal v = e.values().get(m);
            if (k != null && v != null && !ambiguous.contains(k)) {
                out.merge(new Person(k, e.month()), v, BigDecimal::add);
            }
        }
        return out;
    }

    /** 이 시트에서 사람·달마다 첫 행 (경고를 붙일 곳) */
    private static Map<Person, Entry> firstRows(Side side, MigrationFile file, Measure m, boolean byEmp, Set<String> ambiguous) {
        Map<Person, Entry> out = new LinkedHashMap<>();
        for (Entry e : side.entries()) {
            String k = key(e, byEmp);
            if (e.file().getFileId().equals(file.getFileId()) && k != null && e.values().get(m) != null && !ambiguous.contains(k)) {
                out.putIfAbsent(new Person(k, e.month()), e);
            }
        }
        return out;
    }

    /** 사번이 있는 쪽에서 같은 이름이 다른 사번으로 나오면 그 이름으로는 맞출 수 없다 (동명이인) */
    private static Set<String> ambiguousNames(Side... sides) {
        Map<String, Set<String>> emps = new HashMap<>();
        for (Side s : sides) {
            for (Entry e : s.entries()) {
                if (e.name() != null && e.emp() != null) {
                    emps.computeIfAbsent(e.name(), k -> new HashSet<>()).add(e.emp());
                }
            }
        }
        return emps.entrySet().stream().filter(x -> x.getValue().size() > 1).map(Map.Entry::getKey).collect(Collectors.toSet());
    }

    private static String key(Entry e, boolean byEmp) {
        return byEmp ? e.emp() : e.name();
    }

    // ---------- 같은 날 두 시트 ----------

    /** 같은 파일의 다른 날짜 열 시트에 같은 사람이 같은 날 일한 값(0보다 큰 숫자)이 있음 */
    private void sameDayTwoSheets(MigrationFile file, List<ParsedTable.Row> rows, List<MigrationFile> all, List<ValidationIssue> issues) {
        TreeMap<Integer, String> days = DayGrid.columns(List.of(file.getHeaders()));
        String keyCol = keyColumn(file);
        if (days.isEmpty() || keyCol == null) {
            return;
        }
        for (MigrationFile sibling : all) {
            TreeMap<Integer, String> theirDays = DayGrid.columns(List.of(sibling.getHeaders()));
            String theirKey = keyColumn(sibling);
            if (sibling.getFileId().equals(file.getFileId()) || !sibling.getFileName().equals(file.getFileName())
                    || theirDays.isEmpty() || theirKey == null) {
                continue;
            }
            Map<String, ParsedTable.Row> byKey = new HashMap<>();
            stagingRowRepository.findByFileId(sibling.getFileId()).forEach(r -> Optional.ofNullable(norm(r.values().get(theirKey)))
                    .ifPresent(k -> byKey.putIfAbsent(k, r)));
            for (ParsedTable.Row row : rows) {
                String k = norm(row.values().get(keyCol));
                ParsedTable.Row other = k == null ? null : byKey.get(k);
                if (other == null) {
                    continue;
                }
                TreeSet<Integer> both = new TreeSet<>();
                days.forEach((d, h) -> {
                    if (worked(row.values().get(h)) && theirDays.containsKey(d) && worked(other.values().get(theirDays.get(d)))) {
                        both.add(d);
                    }
                });
                if (!both.isEmpty()) {
                    String list = both.stream().map(d -> d + "일").collect(Collectors.joining(", "));
                    issues.add(new ValidationIssue(file.getFileId(), row.rowNo(), null, days.get(both.first()), Severity.WARN,
                            IssueCode.SAME_DAY_TWO_SHEETS,
                            "'" + row.values().get(keyCol) + "'이(가) " + list + "에 '" + sibling.getSheetName() + "' 시트(" + other.rowNo()
                                    + "행)에도 기록돼 있습니다. 같은 날 두 곳에서 일한 것으로 지급·신고됩니다", list, null));
                }
            }
        }
    }

    private static boolean worked(String v) {
        return PayrollColumns.number(v).map(n -> n.signum() > 0).orElse(false);
    }

    // ---------- 읽기 ----------

    /** 같은 이름의 파일(시트들)을 읽는다. 검증 중인 시트는 넘겨받은 행을 쓴다 */
    private Side side(String fileName, List<MigrationFile> all, MigrationFile current, List<ParsedTable.Row> currentRows) {
        List<Entry> entries = new ArrayList<>();
        Map<Measure, String> headers = new EnumMap<>(Measure.class);
        Set<Measure> counted = new HashSet<>();
        boolean hasEmp = false;
        boolean hasName = false;
        Set<String> people = new HashSet<>();
        for (MigrationFile f : all) {
            Map<Measure, String> cols = measures(f);
            if (!f.getFileName().equals(fileName) || cols.isEmpty() || keyColumn(f) == null) {
                continue;
            }
            String empCol = RosterReferenceChecker.findColumn(f, "empNo");
            String nameCol = nameColumn(f);
            hasEmp |= empCol != null;
            hasName |= nameCol != null;
            cols.forEach(headers::putIfAbsent);
            List<String> fh = List.of(f.getHeaders());
            List<ParsedTable.Row> rows = current != null && f.getFileId().equals(current.getFileId())
                    ? currentRows : stagingRowRepository.findByFileId(f.getFileId());
            for (ParsedTable.Row r : rows) {
                Map<Measure, BigDecimal> values = new EnumMap<>(Measure.class);
                cols.forEach((m, h) -> {
                    if (r.formulas().containsKey(h)) {
                        counted.add(m);
                    }
                    Countif.recount(fh, r, h).or(() -> PayrollColumns.number(r.values().get(h))).ifPresent(v -> values.put(m, v));
                });
                String emp = empCol == null ? null : empKey(r.values().get(empCol));
                String name = nameCol == null ? null : norm(r.values().get(nameCol));
                Optional.ofNullable(emp).ifPresent(people::add);
                Optional.ofNullable(name).ifPresent(people::add);
                if ((emp != null || name != null) && !values.isEmpty()) {
                    entries.add(new Entry(f, r.rowNo(), emp, name, PayrollRowChecker.payMonth(f, r), values));
                }
            }
        }
        return new Side(fileName, entries, headers, counted, hasEmp, hasName, people);
    }

    /** 집계 항목 → 이 파일의 열. 두 줄 헤더('연장(h) OT')는 첫 낱말로도 본다 */
    static Map<Measure, String> measures(MigrationFile file) {
        Map<Measure, String> out = new EnumMap<>(Measure.class);
        for (String h : file.getHeaders()) {
            String full = ColumnMappingSuggester.normalize(h);
            String first = h.contains(" ") ? ColumnMappingSuggester.normalize(h.substring(0, h.indexOf(' '))) : full;
            for (Measure m : Measure.values()) {
                if (m.pattern.matcher(full).matches() || m.pattern.matcher(first).matches()) {
                    out.putIfAbsent(m, h);
                }
            }
        }
        return out;
    }

    private static String keyColumn(MigrationFile f) {
        String emp = RosterReferenceChecker.findColumn(f, "empNo");
        return emp != null ? emp : nameColumn(f);
    }

    private static String nameColumn(MigrationFile f) {
        String name = RosterReferenceChecker.findColumn(f, "name");
        if (name != null) {
            return name;
        }
        for (String h : f.getHeaders()) {
            if (NAME_EXTRA.matcher(ColumnMappingSuggester.normalize(h)).matches()) {
                return h;
            }
        }
        return null;
    }

    private static String empKey(String v) {
        String s = norm(v);
        return s != null && s.matches("\\d+") ? s.replaceFirst("^0+(?=\\d)", "") : s;
    }

    private static String norm(String v) {
        if (v == null) {
            return null;
        }
        String s = v.replaceAll("\\s+", "");
        return s.isEmpty() ? null : s;
    }

    private static String who(boolean byEmp, Person p) {
        return (byEmp ? "사번 " + p.key() : "'" + p.key() + "'") + (p.month() == null ? "" : ", " + p.month());
    }

    private static String where(Entry e) {
        return (e.file().getSheetName() == null ? "" : " " + e.file().getSheetName()) + " " + e.rowNo() + "행";
    }

    private static String plain(BigDecimal v) {
        return new DecimalFormat("#,##0.##").format(v);
    }
}
