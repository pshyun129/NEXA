package com.ssafy.migrationservice.domain.migration.validation;

import com.ssafy.migrationservice.domain.migration.entity.MigrationFile;
import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationEntity;
import com.ssafy.migrationservice.domain.migration.mapping.ColumnMappingSuggester;
import com.ssafy.migrationservice.domain.migration.mapping.MappingService;
import com.ssafy.migrationservice.domain.migration.reader.ParsedTable;
import com.ssafy.migrationservice.domain.migration.repository.MigrationFileRepository;
import com.ssafy.migrationservice.domain.migration.repository.StagingRowRepository;
import com.ssafy.migrationservice.domain.migration.validation.issue.IssueCode;
import com.ssafy.migrationservice.domain.migration.validation.issue.Severity;
import com.ssafy.migrationservice.domain.migration.validation.issue.ValidationIssue;
import com.ssafy.migrationservice.domain.migration.validation.schema.EntitySchemas;
import com.ssafy.migrationservice.domain.migration.validation.schema.FieldSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ⑤ 참조: 직원명부가 아닌 파일(급여·연차·근태 …)의 사번이 같은 작업의 직원명부에 있는지, 이름이 같은지 (명부에 한글 표기 열이 있으면 그 이름도 같은 사람).
 * 사번·이름 컬럼은 매핑이 있으면 매핑, 없으면(참고 자료) 직원명부 필드의 라벨·동의어로 찾는다.
 * 숫자 셀로 저장돼 앞자리 0이 빠진 사번은 직원명부 사번 자릿수에 맞춰 0을 채워 대조하고, 그렇게 했다고 알린다.
 * 날짜도 대조한다: 입사일 = 직원명부 입사일, 4대보험 취득일 = 입사일, 상실일 = 퇴사일 다음 날,
 * 급여가 지급된 달이 재직 기간(입사 달 ~ 퇴사 달) 안인지, 직원명부 체류만료일이 지난 달에 급여가 있는지.
 * 직원명부에 사번이 두 번 이상 있으면 어느 날짜가 맞는지 몰라 날짜 대조를 하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class RosterReferenceChecker {

    private final MigrationFileRepository fileRepository;
    private final StagingRowRepository stagingRowRepository;
    private final MappingService mappingService;

    /** 사번 → 이름들 (직원명부에서 사번이 중복이면 이름이 둘 이상. 그 중복은 직원명부 쪽 차단 오류로 따로 나온다) */
    private record Roster(Map<String, Set<String>> namesByEmpNo, Map<String, List<String>> empNosByName, int width,
                          Map<String, LocalDate> hireDates, Map<String, LocalDate> resignDates, boolean hasResignColumn,
                          Map<String, LocalDate> visaExpiry) {
    }

    private static final Pattern ACQUISITION = Pattern.compile("취득|^acq");
    private static final Pattern LOSS = Pattern.compile("^(?!.*(사유|reason)).*(상실|^loss)");
    private static final Pattern VISA_EXPIRY = Pattern.compile("체류(기간)?만료|visaexp");
    // 외국인 명부의 한글 표기 이름 (성명은 여권 영문): 급여·근태 파일이 한글 표기로 적어도 같은 사람으로 본다
    private static final Pattern ALT_NAME = Pattern.compile("^한글(표기|이름|성명|명)|^koreanname");
    private static final double DATE_COLUMN_RATIO = 0.8;

    public List<ValidationIssue> check(MigrationFile file, List<ParsedTable.Row> rows) {
        if (file.getEntity() == MigrationEntity.HR_EMPLOYEE || file.getEntity() == MigrationEntity.HR_ORG) {
            return List.of();
        }
        Optional<Roster> rosterOpt = roster(file.getMigrationId());
        Map<String, String> fieldToColumn = mappingService.fieldToColumn(file.getFileId());
        String empCol = fieldToColumn.getOrDefault("empNo", findColumn(file, "empNo"));
        if (rosterOpt.isEmpty() || empCol == null) {
            return List.of();
        }
        Roster roster = rosterOpt.get();
        String nameCol = fieldToColumn.getOrDefault("name", findColumn(file, "name"));

        String hireCol = dateColumn(rows, fieldToColumn.getOrDefault("hireDate", findColumn(file, "hireDate")));
        List<String> acqCols = Arrays.stream(file.getHeaders())
                .filter(h -> ACQUISITION.matcher(ColumnMappingSuggester.normalize(h)).find() && dateColumn(rows, h) != null).toList();
        String lossCol = Arrays.stream(file.getHeaders())
                .filter(h -> LOSS.matcher(ColumnMappingSuggester.normalize(h)).find() && dateColumn(rows, h) != null)
                .findFirst().orElse(null);

        List<ValidationIssue> issues = new ArrayList<>();
        int padded = 0;
        for (ParsedTable.Row row : rows) {
            String raw = row.values().get(empCol);
            String name = nameCol == null ? null : row.values().get(nameCol);
            if (raw == null) {
                if (name != null) {
                    List<String> byName = roster.empNosByName().getOrDefault(name, List.of());
                    String suggestion = byName.size() == 1 ? byName.get(0) : null;
                    issues.add(new ValidationIssue(file.getFileId(), row.rowNo(), "empNo", empCol, Severity.WARN, IssueCode.EMP_KEY_MISSING,
                            "사번이 비어 있습니다 (이름 '" + name + "')" + (suggestion == null ? "" : ". 직원명부에서 이름으로 찾은 사번: " + suggestion),
                            null, suggestion));
                }
                continue;
            }
            String key = raw;
            if (raw.matches("\\d+") && raw.length() < roster.width()) {
                key = "0".repeat(roster.width() - raw.length()) + raw;
                padded++;
            }
            Set<String> rosterNames = roster.namesByEmpNo().get(key);
            if (rosterNames == null) {
                // 적재하는 파일이면 대상에서 사원을 못 찾아 묶음 전체가 실패하므로 막는다 (참고 자료는 경고만)
                Severity severity = EntitySchemas.of(file.getEntity()).isPresent() ? Severity.BLOCK : Severity.WARN;
                issues.add(new ValidationIssue(file.getFileId(), row.rowNo(), "empNo", empCol, severity, IssueCode.EMP_NOT_IN_ROSTER,
                        "직원명부에 없는 사번입니다: '" + raw + "'", raw, null));
            } else if (name != null && !rosterNames.isEmpty() && !rosterNames.contains(name)) {
                String rosterName = String.join(", ", rosterNames);
                issues.add(new ValidationIssue(file.getFileId(), row.rowNo(), "name", nameCol, Severity.WARN, IssueCode.EMP_NAME_MISMATCH,
                        "사번 " + key + "의 이름이 직원명부('" + rosterName + "')와 다릅니다: '" + name + "'", name, rosterName));
            }
            if (rosterNames != null) {
                dateIssues(file, row, key, roster, hireCol, acqCols, lossCol, issues);
                paidOutsideEmployment(file, row, key, roster, issues);
                paidAfterVisaExpiry(file, row, key, roster, issues);
            }
        }
        if (padded > 0) {
            issues.add(new ValidationIssue(file.getFileId(), null, "empNo", empCol, Severity.WARN, IssueCode.EMPNO_ZERO_PADDED,
                    "사번 " + padded + "건이 숫자로 저장돼 앞자리 0이 빠져 있어, 직원명부 자리수(" + roster.width() + "자리)에 맞춰 대조했습니다",
                    null, null));
        }
        return issues;
    }

    private static void dateIssues(MigrationFile file, ParsedTable.Row row, String empNo, Roster roster, String hireCol,
                                   List<String> acqCols, String lossCol, List<ValidationIssue> issues) {
        LocalDate hire = roster.hireDates().get(empNo);
        LocalDate resign = roster.resignDates().get(empNo);
        if (hire == null) {
            return;     // 직원명부 입사일이 비었거나 사번이 중복: 직원명부 쪽 오류로 따로 나온다
        }
        if (resign != null && resign.isBefore(hire)) {
            resign = null;      // 퇴사일 < 입사일은 직원명부 쪽 오류(DATE_ORDER)라 믿지 않는다
        }
        Long fileId = file.getFileId();
        date(row, hireCol).filter(d -> !d.equals(hire)).ifPresent(d -> issues.add(new ValidationIssue(fileId, row.rowNo(), "hireDate", hireCol,
                Severity.WARN, IssueCode.ROSTER_HIRE_DATE_MISMATCH,
                "입사일 " + d + "이(가) 직원명부 입사일 " + hire + "과(와) 다릅니다", d.toString(), hire.toString())));

        // 4대보험 자격 취득일 = 입사일 (국민연금법·국민건강보험법). 보험별 열이 같으면 한 건으로
        List<String> wrongAcq = acqCols.stream().filter(c -> date(row, c).map(d -> !d.equals(hire)).orElse(false)).toList();
        if (!wrongAcq.isEmpty()) {
            LocalDate acq = date(row, wrongAcq.get(0)).orElseThrow();
            long gap = ChronoUnit.DAYS.between(hire, acq);
            String which = wrongAcq.size() == 1 ? "'" + wrongAcq.get(0) + "'" : "'" + wrongAcq.get(0) + "' 등 " + wrongAcq.size() + "개 취득일";
            issues.add(new ValidationIssue(fileId, row.rowNo(), "hireDate", wrongAcq.get(0), Severity.WARN, IssueCode.INSURANCE_ACQ_DATE_MISMATCH,
                    which + "이 입사일(" + hire + ")보다 " + Math.abs(gap) + "일 " + (gap > 0 ? "늦습니다. 자격 취득일은 입사일이라 지연 신고일 수 있습니다"
                            : "빠릅니다. 입사일이나 취득일 중 하나가 틀렸을 수 있습니다"), acq.toString(), hire.toString()));
        }

        // 상실일 = 퇴사일 다음 날. 직원명부에 퇴사일 열이 없으면 재직 여부를 몰라 보지 않는다
        if (lossCol == null || !roster.hasResignColumn()) {
            return;
        }
        Optional<LocalDate> loss = date(row, lossCol);
        String message = null;
        if (resign != null && loss.isEmpty()) {
            message = "직원명부 퇴사일(" + resign + ")이 있는데 상실일이 비어 있습니다. 상실 신고가 빠졌을 수 있습니다";
        } else if (resign != null && loss.get().equals(resign)) {
            message = "상실일이 퇴사일과 같습니다. 자격 상실일은 퇴사일 다음 날(" + resign.plusDays(1) + ")입니다";
        } else if (resign != null && !loss.get().equals(resign.plusDays(1))) {
            message = "상실일 " + loss.get() + "이(가) 직원명부 퇴사일 다음 날(" + resign.plusDays(1) + ")과 다릅니다";
        } else if (resign == null && loss.isPresent() && !roster.resignDates().containsKey(empNo)) {
            message = "직원명부에는 퇴사일이 없는데 상실일(" + loss.get() + ")이 있습니다";
        }
        if (message != null) {
            issues.add(new ValidationIssue(fileId, row.rowNo(), "resignDate", lossCol, Severity.WARN, IssueCode.INSURANCE_LOSS_DATE_MISMATCH,
                    message, loss.map(LocalDate::toString).orElse(null), resign == null ? null : resign.plusDays(1).toString()));
        }
    }

    /** 급여 행의 귀속월이 직원명부 재직 기간 밖인데 금액이 있으면 (퇴사 처리 누락, 입사 전 지급) */
    private static void paidOutsideEmployment(MigrationFile file, ParsedTable.Row row, String empNo, Roster roster,
                                              List<ValidationIssue> issues) {
        LocalDate hire = roster.hireDates().get(empNo);
        LocalDate resign = roster.resignDates().get(empNo);
        if (hire == null || (resign != null && resign.isBefore(hire))) {
            return;     // 직원명부 날짜가 비었거나 틀림: 직원명부 쪽 오류로 따로 나온다
        }
        YearMonth month = PayrollRowChecker.payMonth(file, row);
        if (month == null || PayrollRowChecker.paid(file, row).map(v -> v.signum() <= 0).orElse(true)) {
            return;
        }
        String why = resign != null && resign.isBefore(month.atDay(1)) ? "퇴사일(" + resign + ") 이후"
                : hire.isAfter(month.atEndOfMonth()) ? "입사일(" + hire + ") 이전" : null;
        if (why != null) {
            issues.add(new ValidationIssue(file.getFileId(), row.rowNo(), null, null, Severity.WARN, IssueCode.PAID_OUTSIDE_EMPLOYMENT,
                    "사번 " + empNo + "에게 직원명부 " + why + "인 " + month + " 급여가 있습니다. 퇴사 처리가 빠졌거나 귀속월이 틀렸을 수 있습니다",
                    month.toString(), null));
        }
    }

    /**
     * 직원명부 체류만료일이 귀속월 전에 지났는데 그 달 급여가 있으면 확인 대상.
     * 만료 전에 연장을 신청했으면 심사 중에도 일할 수 있어 위반으로 단정하지 않는다
     */
    private static void paidAfterVisaExpiry(MigrationFile file, ParsedTable.Row row, String empNo, Roster roster,
                                            List<ValidationIssue> issues) {
        LocalDate expiry = roster.visaExpiry().get(empNo);
        YearMonth month = expiry == null ? null : PayrollRowChecker.payMonth(file, row);
        if (month == null || !expiry.isBefore(month.atDay(1)) || PayrollRowChecker.paid(file, row).map(v -> v.signum() <= 0).orElse(true)) {
            return;
        }
        issues.add(new ValidationIssue(file.getFileId(), row.rowNo(), null, null, Severity.WARN, IssueCode.VISA_EXPIRED_PAID,
                "사번 " + empNo + "의 직원명부 체류만료일(" + expiry + ")이 지났는데 " + month + " 급여가 있습니다. "
                        + "체류기간 연장 허가(또는 만료 전 신청) 기록을 확인해 주세요", month.toString(), null));
    }

    private static String header(MigrationFile file, Pattern p) {
        return Arrays.stream(file.getHeaders()).filter(h -> p.matcher(ColumnMappingSuggester.normalize(h)).find()).findFirst().orElse(null);
    }

    private static Optional<LocalDate> date(ParsedTable.Row row, String column) {
        return column == null ? Optional.empty() : FieldValueChecker.parseDate(row.values().get(column));
    }

    /** 값이 있는 칸의 80% 이상이 날짜인 열이면 그 열, 아니면 null (이름만 같은 '상실사유' 같은 열을 거른다) */
    private static String dateColumn(List<ParsedTable.Row> rows, String column) {
        if (column == null) {
            return null;
        }
        List<String> values = rows.stream().map(r -> r.values().get(column)).filter(Objects::nonNull).toList();
        long dates = values.stream().filter(v -> FieldValueChecker.parseDate(v).isPresent()).count();
        return !values.isEmpty() && dates >= values.size() * DATE_COLUMN_RATIO ? column : null;
    }

    /** 같은 작업의 직원명부 (여러 개면 마지막에 올린 것). 사번·이름이 매핑돼 있어야 함 */
    private Optional<Roster> roster(Long migrationId) {
        Optional<MigrationFile> file = fileRepository.findByMigrationIdOrderByFileIdAsc(migrationId).stream()
                .filter(f -> f.getEntity() == MigrationEntity.HR_EMPLOYEE)
                .max(Comparator.comparing(MigrationFile::getFileId));
        if (file.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> f2c = mappingService.fieldToColumn(file.get().getFileId());
        String empCol = f2c.get("empNo");
        String nameCol = f2c.get("name");
        String hireCol = f2c.get("hireDate");
        String resignCol = f2c.get("resignDate");
        if (empCol == null) {
            return Optional.empty();
        }
        Map<String, Set<String>> namesByEmpNo = new HashMap<>();
        Map<String, List<String>> byName = new HashMap<>();
        Map<String, LocalDate> hires = new HashMap<>();
        Map<String, LocalDate> resigns = new HashMap<>();
        Map<String, LocalDate> visas = new HashMap<>();
        List<ParsedTable.Row> rosterRows = stagingRowRepository.findByFileId(file.get().getFileId());
        // 이름으로 찾은 열이라 날짜 비율은 보지 않는다 (만료일만 다른 형식으로 적은 외국인 명부가 있어 읽히는 값만 쓴다)
        String visaCol = header(file.get(), VISA_EXPIRY);
        String altNameCol = header(file.get(), ALT_NAME);
        Set<String> seen = new HashSet<>();
        Set<String> duplicated = new HashSet<>();
        for (ParsedTable.Row r : rosterRows) {
            String emp = r.values().get(empCol);
            String name = nameCol == null ? null : r.values().get(nameCol);
            String altName = altNameCol == null ? null : r.values().get(altNameCol);
            if (emp == null) {
                continue;
            }
            if (!seen.add(emp)) {
                duplicated.add(emp);
            }
            date(r, hireCol).ifPresent(d -> hires.put(emp, d));
            date(r, resignCol).ifPresent(d -> resigns.put(emp, d));
            date(r, visaCol).ifPresent(d -> visas.put(emp, d));
            Set<String> names = namesByEmpNo.computeIfAbsent(emp, k -> new LinkedHashSet<>());
            if (name != null) {
                names.add(name);
                byName.computeIfAbsent(name, k -> new ArrayList<>()).add(emp);
            }
            if (altName != null && !altName.equals(name)) {
                names.add(altName);
                byName.computeIfAbsent(altName, k -> new ArrayList<>()).add(emp);
            }
        }
        int width = namesByEmpNo.keySet().stream().filter(k -> k.matches("\\d+")).mapToInt(String::length).max().orElse(0);
        hires.keySet().removeAll(duplicated);
        resigns.keySet().removeAll(duplicated);
        visas.keySet().removeAll(duplicated);
        return Optional.of(new Roster(namesByEmpNo, byName, width, hires, resigns, resignCol != null, visas));
    }

    /**
     * 매핑이 없는 파일: 직원명부 필드(사번·이름)의 이름·라벨·동의어와 같은 헤더.
     * 없으면 두 줄 헤더('사번\nID' → '사번 ID')의 첫 낱말로 한 번 더 찾는다
     */
    static String findColumn(MigrationFile file, String field) {
        FieldSpec spec = EntitySchemas.of(MigrationEntity.HR_EMPLOYEE).flatMap(s -> s.field(field)).orElseThrow();
        List<String> names = new ArrayList<>(spec.synonyms());
        names.add(spec.name());
        names.add(spec.label());
        List<String> wanted = names.stream().map(ColumnMappingSuggester::normalize).toList();
        for (String h : file.getHeaders()) {
            if (wanted.contains(ColumnMappingSuggester.normalize(h))) {
                return h;
            }
        }
        for (String h : file.getHeaders()) {
            if (h.contains(" ") && wanted.contains(ColumnMappingSuggester.normalize(h.substring(0, h.indexOf(' '))))) {
                return h;
            }
        }
        return null;
    }
}
