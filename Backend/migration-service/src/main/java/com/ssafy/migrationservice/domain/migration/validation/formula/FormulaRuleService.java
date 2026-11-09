package com.ssafy.migrationservice.domain.migration.validation.formula;

import com.ssafy.migrationservice.domain.migration.dto.FormulaRulesResponse;
import com.ssafy.migrationservice.domain.migration.entity.MigrationFile;
import com.ssafy.migrationservice.domain.migration.reader.ParsedTable;
import com.ssafy.migrationservice.domain.migration.repository.MigrationFileRepository;
import com.ssafy.migrationservice.domain.migration.repository.StagingRowRepository;
import com.ssafy.migrationservice.domain.migration.validation.formula.FormulaInspector.ColumnRule;
import com.ssafy.migrationservice.domain.migration.validation.formula.FormulaInspector.ColumnSummary;
import com.ssafy.migrationservice.domain.migration.validation.issue.IssueCode;
import com.ssafy.migrationservice.domain.migration.validation.issue.Severity;
import com.ssafy.migrationservice.domain.migration.validation.issue.ValidationIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 엑셀 수식에서 회사 규칙을 뽑고(열마다 대표 수식), 규칙에서 벗어난 칸을 이슈로 만든다.
 * 같은 작업에 같은 파일의 같은 양식 시트(월별 급여 시트 등)가 있으면 시트끼리 대표 수식도 비교한다.
 * 합계 열 검산(RecalcChecker)도 여기서 같이 돌린다.
 */
@Component
@RequiredArgsConstructor
public class FormulaRuleService {

    private final MigrationFileRepository fileRepository;
    private final StagingRowRepository stagingRowRepository;

    public record Inspection(List<FormulaRulesResponse.Column> columns, List<ValidationIssue> issues) {
    }

    /** 같은 작업에서 파일 이름·헤더가 같은 다른 시트 */
    public List<MigrationFile> siblings(MigrationFile file) {
        return fileRepository.findByMigrationIdOrderByFileIdAsc(file.getMigrationId()).stream()
                .filter(f -> !f.getFileId().equals(file.getFileId())
                        && f.getFileName().equals(file.getFileName())
                        && !Objects.equals(f.getSheetName(), file.getSheetName())
                        && Arrays.equals(f.getHeaders(), file.getHeaders()))
                .toList();
    }

    public Inspection inspect(MigrationFile file, List<ParsedTable.Row> rows) {
        List<String> headers = List.of(file.getHeaders());
        Map<String, Map<String, ColumnSummary>> others = new LinkedHashMap<>();
        for (MigrationFile s : siblings(file)) {
            others.put(s.getSheetName(), FormulaInspector.summarize(headers, stagingRowRepository.findByFileId(s.getFileId())));
        }
        List<FormulaRulesResponse.Column> columns = new ArrayList<>();
        List<ValidationIssue> issues = new ArrayList<>();
        List<ColumnRule> rules = FormulaInspector.inspect(headers, rows, List.copyOf(others.values()));
        for (ColumnRule rule : rules) {
            String col = rule.column();
            String formula = FormulaPattern.readable(rule.pattern(), headers);
            rule.hardcodedRows().forEach((rowNo, v) -> issues.add(issue(file, rowNo, col, IssueCode.HARDCODED_VALUE,
                    "'" + col + "' 열은 수식(" + formula + ")인데 이 칸은 값이 직접 입력돼 있습니다. 수식을 지우고 숫자를 넣었는지 확인해 주세요", v)));
            rule.blankRows().forEach(rowNo -> issues.add(issue(file, rowNo, col, IssueCode.BLANK_IN_FORMULA_COLUMN,
                    "'" + col + "' 열은 수식(" + formula + ")인데 이 칸은 비어 있습니다. 새로 추가한 행에 수식을 빠뜨렸을 수 있습니다", null)));
            rule.otherRows().forEach((rowNo, p) -> {
                String mine = FormulaPattern.readable(p, headers);
                issues.add(issue(file, rowNo, col, IssueCode.FORMULA_INCONSISTENT,
                        "'" + col + "' 수식이 다른 행과 다릅니다. 이 행 " + mine + ", 대부분 " + formula, mine));
            });

            String across = "NO_OTHER_SHEET";
            String otherFormula = null;
            List<String> otherSheets = List.of();
            if (rule.formulaColumn() && !others.isEmpty()) {
                Map<String, List<String>> sheetsBySig = new LinkedHashMap<>();
                Map<String, String> patternBySig = new LinkedHashMap<>();
                others.forEach((sheet, summary) -> {
                    ColumnSummary s = summary.get(col);
                    if (s != null) {
                        sheetsBySig.computeIfAbsent(s.signature(), k -> new ArrayList<>()).add(sheet);
                        patternBySig.putIfAbsent(s.signature(), s.pattern());
                    }
                });
                if (!sheetsBySig.isEmpty()) {
                    int own = 1 + sheetsBySig.getOrDefault(rule.signature(), List.of()).size();
                    Map.Entry<String, List<String>> top = sheetsBySig.entrySet().stream()
                            .max(Map.Entry.comparingByValue((a, b) -> Integer.compare(a.size(), b.size()))).orElseThrow();
                    if (!top.getKey().equals(rule.signature()) && top.getValue().size() > own) {
                        across = "DIFFERENT";
                        otherFormula = FormulaPattern.readable(patternBySig.get(top.getKey()), headers);
                        otherSheets = top.getValue();
                        issues.add(new ValidationIssue(file.getFileId(), null, null, col, Severity.WARN,
                                IssueCode.FORMULA_DIFFERS_ACROSS_SHEETS,
                                "'" + col + "' 수식이 같은 양식의 다른 시트(" + String.join(", ", otherSheets) + ")와 다릅니다. 이 시트 "
                                        + formula + ", 다른 시트 " + otherFormula, formula, otherFormula));
                    } else {
                        across = "SAME";
                    }
                }
            }
            columns.add(new FormulaRulesResponse.Column(col, formula, rule.formulaColumn(), rule.dataRows(), rule.formulaRows(),
                    List.copyOf(rule.hardcodedRows().keySet()), rule.blankRows(),
                    rule.otherRows().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                            e -> FormulaPattern.readable(e.getValue(), headers), (a, b) -> a, LinkedHashMap::new)),
                    across, otherFormula, otherSheets));
        }
        issues.addAll(RecalcChecker.check(file.getFileId(), headers, rows, rules));
        return new Inspection(columns, issues);
    }

    private static ValidationIssue issue(MigrationFile file, int rowNo, String column, IssueCode code, String message, String value) {
        return new ValidationIssue(file.getFileId(), rowNo, null, column, Severity.WARN, code, message, value, null);
    }
}
