package com.ssafy.migrationservice.domain.migration.validation.formula;

import com.ssafy.migrationservice.domain.migration.reader.ParsedTable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 열마다 수식 규칙을 뽑는다. 데이터 행의 90% 이상이 수식인 열을 '수식 열'로 보고
 * 대표 수식(가장 많은 모양), 숫자가 직접 들어간 행, 비어 있는 행, 대표와 모양이 다른 행을 찾는다.
 * 대표 수식이 둘로 갈리면(30% 넘게 다른 모양) 같은 양식의 다른 시트에서 쓰는 모양을 고른다.
 * 참조하는 시트만 다른 행(총괄 시트의 부서별 행 등)은 대표와 같은 규칙으로 본다.
 */
public final class FormulaInspector {

    static final double FORMULA_COLUMN_COVERAGE = 0.9;
    static final double SPLIT_RATIO = 0.7;
    private static final int MIN_FORMULA_ROWS = 3;

    private FormulaInspector() {
    }

    /** 다른 시트와 비교할 때 쓰는 열 요약: 대표 수식 모양과 서명 */
    public record ColumnSummary(String pattern, String signature) {
    }

    /**
     * @param hardcodedRows  수식 열인데 값을 직접 입력한 행 → 값
     * @param blankRows      수식 열인데 비어 있는 행
     * @param otherRows      대표와 모양이 다른 행 → 그 행의 수식 모양
     */
    public record ColumnRule(String column, String pattern, String signature, int dataRows, int formulaRows,
                             boolean formulaColumn, Map<Integer, String> hardcodedRows, List<Integer> blankRows,
                             Map<Integer, String> otherRows) {
    }

    public static List<ColumnRule> inspect(List<String> headers, List<ParsedTable.Row> rows,
                                           List<Map<String, ColumnSummary>> siblings) {
        List<ColumnRule> rules = new ArrayList<>();
        for (String column : headers) {
            Map<Integer, String> patterns = new LinkedHashMap<>();
            for (ParsedTable.Row row : rows) {
                String f = row.formulas().get(column);
                if (f != null) {
                    patterns.put(row.rowNo(), FormulaPattern.pattern(f, row.rowNo()));
                }
            }
            if (patterns.size() < MIN_FORMULA_ROWS) {
                continue;
            }
            String dominant = dominant(patterns, column, siblings);
            boolean formulaColumn = patterns.size() >= rows.size() * FORMULA_COLUMN_COVERAGE;
            Map<Integer, String> hardcoded = new LinkedHashMap<>();
            List<Integer> blank = new ArrayList<>();
            if (formulaColumn) {
                for (ParsedTable.Row row : rows) {
                    if (patterns.containsKey(row.rowNo()) || row.errors().containsKey(column)) {
                        continue;
                    }
                    String v = row.values().get(column);
                    if (v == null) {
                        blank.add(row.rowNo());
                    } else {
                        hardcoded.put(row.rowNo(), v);
                    }
                }
            }
            Map<Integer, String> other = new LinkedHashMap<>();
            String dominantLoose = FormulaPattern.loose(dominant);
            patterns.forEach((rowNo, p) -> {
                if (!p.equals(dominant) && !FormulaPattern.loose(p).equals(dominantLoose)) {
                    other.put(rowNo, p);
                }
            });
            rules.add(new ColumnRule(column, dominant, FormulaPattern.signature(dominant), rows.size(), patterns.size(),
                    formulaColumn, hardcoded, blank, other));
        }
        return rules;
    }

    /** 다른 시트 비교용 요약 (수식 열만) */
    public static Map<String, ColumnSummary> summarize(List<String> headers, List<ParsedTable.Row> rows) {
        Map<String, ColumnSummary> m = new LinkedHashMap<>();
        for (ColumnRule r : inspect(headers, rows, List.of())) {
            if (r.formulaColumn()) {
                m.put(r.column(), new ColumnSummary(r.pattern(), r.signature()));
            }
        }
        return m;
    }

    private static String dominant(Map<Integer, String> patterns, String column, List<Map<String, ColumnSummary>> siblings) {
        Map<String, Long> counts = patterns.values().stream()
                .collect(Collectors.groupingBy(p -> p, LinkedHashMap::new, Collectors.counting()));
        String top = counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        if (counts.get(top) >= patterns.size() * SPLIT_RATIO) {
            return top;
        }
        // 둘로 갈림: 다른 시트의 대표 수식과 같은 모양이 있으면 그쪽
        return siblings.stream()
                .map(s -> s.get(column))
                .filter(Objects::nonNull)
                .map(ColumnSummary::pattern)
                .filter(counts::containsKey)
                .max(Comparator.comparingLong(counts::get))
                .orElse(top);
    }
}
