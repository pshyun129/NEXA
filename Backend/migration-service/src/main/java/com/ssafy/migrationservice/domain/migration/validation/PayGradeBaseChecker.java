package com.ssafy.migrationservice.domain.migration.validation;

import com.ssafy.migrationservice.domain.migration.entity.MigrationFile;
import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationEntity;
import com.ssafy.migrationservice.domain.migration.load.GradeResolver;
import com.ssafy.migrationservice.domain.migration.mapping.MappingService;
import com.ssafy.migrationservice.domain.migration.reader.ParsedTable;
import com.ssafy.migrationservice.domain.migration.repository.MigrationFileRepository;
import com.ssafy.migrationservice.domain.migration.repository.StagingRowRepository;
import com.ssafy.migrationservice.domain.migration.validation.issue.IssueCode;
import com.ssafy.migrationservice.domain.migration.validation.issue.Severity;
import com.ssafy.migrationservice.domain.migration.validation.issue.ValidationIssue;
import com.ssafy.migrationservice.domain.migration.validation.payroll.PayrollColumns;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 지난 급여의 기본급을 직원명부 직급별로 묶어, 같은 직급인데 기본급이 다른 행을 알린다.
 * 적재하면 직급마다 가장 많은 기본급(modeAmount)으로 payroll 직급별 기본급을 만들기 때문에 다른 값은 그 표에 들어가지 않는다.
 * 여러 달이 있으면 가장 최근 달만 본다 (중간에 오른 기본급은 정상). 연봉제처럼 직급 안에서 제각각이면 행마다 알리지 않고 한 건으로 묶는다.
 */
@Component
@RequiredArgsConstructor
public class PayGradeBaseChecker {

    private static final double COLLAPSE = 0.3;   // 다른 값인 행이 이 비율을 넘으면 파일 단위 한 건

    private final MigrationFileRepository fileRepository;
    private final StagingRowRepository stagingRowRepository;
    private final MappingService mappingService;

    public List<ValidationIssue> check(MigrationFile file, List<ParsedTable.Row> rows) {
        if (file.getEntity() != MigrationEntity.PAY_RESULT_HISTORY) {
            return List.of();
        }
        Map<String, String> f2c = mappingService.fieldToColumn(file.getFileId());
        String empCol = f2c.get("empNo");
        String baseCol = f2c.get("basePay");
        Map<String, String> gradeOf = rosterGrades(file.getMigrationId());
        if (empCol == null || baseCol == null || gradeOf.isEmpty()) {
            return List.of();
        }
        YearMonth latest = rows.stream().map(r -> PayrollRowChecker.payMonth(file, r)).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        Map<String, List<ParsedTable.Row>> byGrade = new LinkedHashMap<>();
        for (ParsedTable.Row r : rows) {
            String grade = gradeOf.get(r.values().get(empCol));
            if (grade != null && PayrollColumns.number(r.values().get(baseCol)).isPresent()
                    && (latest == null || latest.equals(PayrollRowChecker.payMonth(file, r)))) {
                byGrade.computeIfAbsent(grade, k -> new ArrayList<>()).add(r);
            }
        }

        List<ValidationIssue> perRow = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, List<ParsedTable.Row>> e : byGrade.entrySet()) {
            List<BigDecimal> amounts = e.getValue().stream().map(r -> PayrollColumns.number(r.values().get(baseCol)).orElseThrow()).toList();
            BigDecimal mode = modeAmount(amounts).orElseThrow();
            long same = amounts.stream().filter(a -> a.compareTo(mode) == 0).count();
            checked += amounts.size();
            for (ParsedTable.Row r : e.getValue()) {
                BigDecimal a = PayrollColumns.number(r.values().get(baseCol)).orElseThrow();
                if (a.compareTo(mode) != 0) {
                    perRow.add(new ValidationIssue(file.getFileId(), r.rowNo(), "basePay", baseCol, Severity.WARN, IssueCode.PAY_GRADE_BASE_VARIES,
                            "직급 '" + e.getKey() + "'의 기본급은 대부분(" + same + "명) " + won(mode) + "원인데 이 행은 " + won(a)
                                    + "원입니다. 적재하면 이 직급의 기본급은 " + won(mode) + "원으로 만들어집니다",
                            r.values().get(baseCol), mode.toPlainString()));
                }
            }
        }
        if (perRow.size() > checked * COLLAPSE) {
            return List.of(new ValidationIssue(file.getFileId(), null, "basePay", baseCol, Severity.WARN, IssueCode.PAY_GRADE_BASE_VARIES,
                    "같은 직급인데 기본급이 다른 행이 " + perRow.size() + "/" + checked + "행입니다. 연봉제처럼 사람마다 기본급이 다르면 "
                            + "직급별 기본급 하나로 담을 수 없습니다. 적재하면 직급마다 가장 많은 값으로 만들어집니다", null, null));
        }
        return perRow;
    }

    /** 가장 많이 나온 금액. 같으면 큰 금액 (적재 쪽과 같은 규칙) */
    public static Optional<BigDecimal> modeAmount(List<BigDecimal> amounts) {
        Map<BigDecimal, Long> counts = new HashMap<>();
        amounts.forEach(a -> counts.merge(a.stripTrailingZeros(), 1L, Long::sum));
        return counts.entrySet().stream()
                .max(Map.Entry.<BigDecimal, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey);
    }

    /** 같은 작업 직원명부의 사번 → 직급 (가장 최근에 올린 명부) */
    private Map<String, String> rosterGrades(Long migrationId) {
        Optional<MigrationFile> roster = fileRepository.findByMigrationIdOrderByFileIdAsc(migrationId).stream()
                .filter(f -> f.getEntity() == MigrationEntity.HR_EMPLOYEE)
                .max(Comparator.comparing(MigrationFile::getFileId));
        if (roster.isEmpty()) {
            return Map.of();
        }
        Map<String, String> f2c = mappingService.fieldToColumn(roster.get().getFileId());
        String empCol = f2c.get("empNo");
        if (empCol == null) {
            return Map.of();
        }
        // 직급 표기는 적재와 같게 (호봉 열 합치기, 직급 열이 없으면 직위)
        Map<String, String> out = new HashMap<>();
        for (ParsedTable.Row r : stagingRowRepository.findByFileId(roster.get().getFileId())) {
            String emp = r.values().get(empCol);
            GradeResolver.Raw grade = GradeResolver.raw(f2c, r);
            if (emp != null && grade != null) {
                out.put(emp, grade.level() == null ? grade.name() : grade.name() + " " + grade.level() + "호봉");
            }
        }
        return out;
    }

    private static String won(BigDecimal v) {
        return String.format("%,d", v.longValue());
    }
}
