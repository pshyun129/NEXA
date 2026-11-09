package com.ssafy.migrationservice.domain.migration.validation.formula;

import com.ssafy.migrationservice.domain.migration.reader.ParsedTable;
import com.ssafy.migrationservice.domain.migration.validation.formula.FormulaInspector.ColumnRule;
import com.ssafy.migrationservice.domain.migration.validation.issue.IssueCode;
import com.ssafy.migrationservice.domain.migration.validation.issue.Severity;
import com.ssafy.migrationservice.domain.migration.validation.issue.ValidationIssue;
import com.ssafy.migrationservice.domain.migration.validation.payroll.PayrollColumns;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.ssafy.migrationservice.domain.migration.validation.payroll.PayrollColumns.DEDUCTION_TOTAL;
import static com.ssafy.migrationservice.domain.migration.validation.payroll.PayrollColumns.GROSS;
import static com.ssafy.migrationservice.domain.migration.validation.payroll.PayrollColumns.NET;
import static com.ssafy.migrationservice.domain.migration.validation.payroll.PayrollColumns.NOT_AMOUNT;
import static com.ssafy.migrationservice.domain.migration.validation.payroll.PayrollColumns.PAY_ITEM;
import static com.ssafy.migrationservice.domain.migration.validation.payroll.PayrollColumns.SUBTOTAL;
import static com.ssafy.migrationservice.domain.migration.validation.payroll.PayrollColumns.find;
import static com.ssafy.migrationservice.domain.migration.validation.payroll.PayrollColumns.normalize;
import static com.ssafy.migrationservice.domain.migration.validation.payroll.PayrollColumns.number;
import static com.ssafy.migrationservice.domain.migration.validation.payroll.PayrollColumns.numeric;

/**
 * 검산: 합계 열이 항목 열의 합과 맞는지 행마다 다시 계산한다.
 * - 수식 열: 대표 수식이 같은 행 칸의 덧셈·뺄셈·SUM이면 그 수식으로 다시 계산한다 (숫자로 바꿔 넣은 칸이 맞는지, 금액 차이)
 * - 수식이 없는 파일(CSV·값만 붙여 넣은 엑셀): 열 이름으로 지급계·공제계·실지급을 찾아 항목 합으로 추정해 다시 계산한다
 * - 지급계·공제계 수식 범위에 들어가지 않은 지급·공제 항목 열을 찾는다 (SUM 범위 누락)
 * - 같은 행을 세는 COUNTIF(근무표 코드별 횟수)는 범위를 다시 세어 저장값과 비교한다
 * ROUND·VLOOKUP처럼 다른 계산이 섞인 수식은 다시 계산하지 않는다.
 */
public final class RecalcChecker {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");   // 엑셀 실수 오차만 무시 (연차 0.5일은 잡는다)

    private static final Pattern TOKEN = Pattern.compile("\\s*(SUM\\(|\\$?[A-Z]{1,3}\\[0]|[-+(),:])");

    private RecalcChecker() {
    }

    public static List<ValidationIssue> check(Long fileId, List<String> headers, List<ParsedTable.Row> rows, List<ColumnRule> rules) {
        List<ValidationIssue> issues = new ArrayList<>();
        Map<String, ColumnRule> formulaColumns = rules.stream().filter(ColumnRule::formulaColumn)
                .collect(Collectors.toMap(ColumnRule::column, r -> r, (a, b) -> a, LinkedHashMap::new));

        // 수식 열: 대표 수식으로 다시 계산
        for (ColumnRule rule : formulaColumns.values()) {
            Map<Integer, Integer> terms = linear(rule.pattern());
            int target = headers.indexOf(rule.column());
            if (terms == null || terms.size() < 2 || terms.containsKey(target) || terms.keySet().stream().anyMatch(c -> c >= headers.size())) {
                continue;
            }
            String formula = FormulaPattern.readable(rule.pattern(), headers);
            for (Mismatch m : mismatches(headers, rows, target, terms)) {
                issues.add(rowIssue(fileId, m, rule.column(), "이 열의 수식 규칙 " + formula));
            }
        }

        // 같은 행을 세는 COUNTIF: 범위를 다시 센 값과 저장값이 다르면 공백 등이 붙어 세지 않은 칸이 있다
        for (ParsedTable.Row row : rows) {
            for (String h : headers) {
                Optional<BigDecimal> recount = Countif.recount(headers, row, h);
                Optional<BigDecimal> stored = number(row.values().get(h));
                if (recount.isPresent() && stored.isPresent() && recount.get().compareTo(stored.get()) != 0) {
                    issues.add(new ValidationIssue(fileId, row.rowNo(), null, h, Severity.WARN, IssueCode.RECALC_MISMATCH,
                            "'" + h + "' 값 " + plain(stored.get()) + " ≠ 범위를 다시 센 값 " + plain(recount.get())
                                    + ". 세는 칸 중에 앞뒤 공백이나 보이지 않는 글자가 붙어 COUNTIF가 세지 못한 칸이 있을 수 있습니다",
                            plain(stored.get()), recount.get().toPlainString()));
                }
            }
        }

        int gross = find(headers, GROSS);
        int ded = find(headers, DEDUCTION_TOTAL);
        int net = find(headers, NET);
        List<Integer> payItems = gross < 0 ? List.of() : amountColumns(headers, rows, 0, gross, true);
        List<Integer> dedItems = gross < 0 || ded < gross ? List.of() : amountColumns(headers, rows, gross + 1, ded, false);

        // 지급계·공제계 수식 범위에 빠진 항목 열
        missingItems(fileId, headers, rows, formulaColumns, gross, payItems, "지급", issues);
        missingItems(fileId, headers, rows, formulaColumns, ded, dedItems, "공제", issues);

        // 수식이 없는 합계 열: 열 이름으로 추정한 항목 합으로 다시 계산. 사이에 소계 열이 있으면 구성을 알 수 없어 하지 않는다
        if (gross >= 0 && payItems.size() >= 2 && !hasSubtotal(headers, 0, gross)) {
            estimate(fileId, headers, rows, formulaColumns, gross, plus(payItems), "지급 항목", issues);
        }
        if (ded >= 0 && dedItems.size() >= 2 && !hasSubtotal(headers, gross + 1, ded)) {
            estimate(fileId, headers, rows, formulaColumns, ded, plus(dedItems), "공제 항목", issues);
        }
        if (net >= 0 && gross >= 0 && ded >= 0) {
            Map<Integer, Integer> terms = new LinkedHashMap<>();
            terms.put(gross, 1);
            terms.put(ded, -1);
            estimate(fileId, headers, rows, formulaColumns, net, terms, "지급계 − 공제계", issues);
        }
        return issues;
    }

    /** 같은 행 칸의 덧셈·뺄셈·SUM만으로 된 수식 모양이면 열 번호 → 계수(+1·-1), 아니면 null */
    static Map<Integer, Integer> linear(String pattern) {
        List<String> tokens = new ArrayList<>();
        Matcher m = TOKEN.matcher(pattern);
        int pos = 0;
        while (pos < pattern.length()) {
            if (!m.find(pos) || m.start() != pos) {
                return null;
            }
            tokens.add(m.group(1));
            pos = m.end();
        }
        Parser p = new Parser(tokens);
        try {
            p.expr(1);
        } catch (IllegalStateException e) {
            return null;
        }
        if (p.i != tokens.size()) {
            return null;
        }
        p.terms.values().removeIf(c -> c == 0);
        return p.terms;
    }

    private static final class Parser {
        private final List<String> tokens;
        private final Map<Integer, Integer> terms = new LinkedHashMap<>();
        private int i;

        Parser(List<String> tokens) {
            this.tokens = tokens;
        }

        void expr(int sign) {
            term(sign);
            while (i < tokens.size() && (tokens.get(i).equals("+") || tokens.get(i).equals("-"))) {
                int s = tokens.get(i++).equals("+") ? sign : -sign;
                term(s);
            }
        }

        void term(int sign) {
            String t = next();
            if (t.equals("SUM(") || t.equals("(")) {
                expr(sign);
                while (t.equals("SUM(") && peek(",")) {
                    i++;
                    expr(sign);
                }
                expect(")");
            } else if (t.equals("-")) {
                term(-sign);
            } else if (t.contains("[")) {
                int from = column(t);
                int to = from;
                if (peek(":")) {
                    i++;
                    to = column(next());
                }
                for (int c = Math.min(from, to); c <= Math.max(from, to); c++) {
                    terms.merge(c, sign, Integer::sum);
                }
            } else {
                throw new IllegalStateException(t);
            }
        }

        private int column(String ref) {
            if (!ref.contains("[")) {
                throw new IllegalStateException(ref);
            }
            return FormulaPattern.columnIndex(ref.substring(ref.startsWith("$") ? 1 : 0, ref.indexOf('[')));
        }

        private boolean peek(String s) {
            return i < tokens.size() && tokens.get(i).equals(s);
        }

        private String next() {
            if (i >= tokens.size()) {
                throw new IllegalStateException("end");
            }
            return tokens.get(i++);
        }

        private void expect(String s) {
            if (!next().equals(s)) {
                throw new IllegalStateException(s);
            }
        }
    }

    /**
     * @param explainedBy 차이가 이 열 값과 같음 (그 열이 합에 빠졌거나 더 들어감)
     */
    record Mismatch(int rowNo, BigDecimal actual, BigDecimal expected, BigDecimal diff, String explainedBy) {
    }

    private static List<Mismatch> mismatches(List<String> headers, List<ParsedTable.Row> rows, int target,
                                             Map<Integer, Integer> terms) {
        String targetHeader = headers.get(target);
        List<Mismatch> out = new ArrayList<>();
        for (ParsedTable.Row row : rows) {
            if (!row.errors().isEmpty() && (row.errors().containsKey(targetHeader)
                    || terms.keySet().stream().anyMatch(c -> row.errors().containsKey(headers.get(c))))) {
                continue;   // 수식 오류는 FORMULA_ERROR로 따로 알린다
            }
            String raw = row.values().get(targetHeader);
            Optional<BigDecimal> actual = number(raw);
            if (raw == null || actual.isEmpty()) {
                continue;
            }
            BigDecimal expected = BigDecimal.ZERO;
            boolean ok = true;
            for (Map.Entry<Integer, Integer> t : terms.entrySet()) {
                String v = row.values().get(headers.get(t.getKey()));
                Optional<BigDecimal> n = v == null ? Optional.of(BigDecimal.ZERO) : number(v);
                if (n.isEmpty()) {
                    ok = false;
                    break;
                }
                expected = expected.add(n.get().multiply(BigDecimal.valueOf(t.getValue())));
            }
            BigDecimal diff = actual.get().subtract(expected);
            if (!ok || diff.abs().compareTo(TOLERANCE) < 0) {
                continue;
            }
            String explainedBy = terms.keySet().stream()
                    .filter(c -> number(row.values().get(headers.get(c))).map(v -> v.abs().compareTo(diff.abs()) == 0).orElse(false))
                    .map(headers::get).findFirst().orElse(null);
            out.add(new Mismatch(row.rowNo(), actual.get(), expected, diff, explainedBy));
        }
        return out;
    }

    private static void missingItems(Long fileId, List<String> headers, List<ParsedTable.Row> rows,
                                     Map<String, ColumnRule> formulaColumns, int total, List<Integer> items, String kind,
                                     List<ValidationIssue> issues) {
        ColumnRule rule = total < 0 ? null : formulaColumns.get(headers.get(total));
        Map<Integer, Integer> terms = rule == null ? null : linear(rule.pattern());
        if (terms == null || terms.values().stream().anyMatch(c -> c < 0)) {
            return;
        }
        // 합계가 소계 열을 더하면 그 소계 수식이 더하는 열도 들어간 것으로 본다 (수당계 = SUM(연장수당:휴일수당))
        Set<Integer> covered = new HashSet<>(terms.keySet());
        for (int c : terms.keySet()) {
            if (c >= headers.size() || !SUBTOTAL.matcher(normalize(headers.get(c))).matches()) {
                continue;
            }
            ColumnRule sub = formulaColumns.get(headers.get(c));
            Map<Integer, Integer> subTerms = sub == null ? null : linear(sub.pattern());
            if (subTerms == null) {
                return;     // 값만 있는 소계: 무엇을 더했는지 모름
            }
            covered.addAll(subTerms.keySet());
        }
        List<String> missing = items.stream().filter(c -> !covered.contains(c)).map(headers::get).toList();
        if (missing.isEmpty()) {
            return;
        }
        long affected = rows.stream().filter(r -> missing.stream()
                .anyMatch(h -> number(r.values().get(h)).map(v -> v.signum() != 0).orElse(false))).count();
        String names = missing.stream().map(h -> "'" + h + "'").collect(Collectors.joining(", "));
        String column = headers.get(total);
        issues.add(new ValidationIssue(fileId, null, null, column, Severity.WARN, IssueCode.TOTAL_ITEM_MISSING,
                "'" + column + "' 수식 " + FormulaPattern.readable(rule.pattern(), headers) + "에 " + kind + " 항목 " + names
                        + " 열이 들어 있지 않습니다. 이 값이 있는 " + affected + "행은 '" + column + "'에서 그만큼 빠졌습니다",
                String.join(", ", missing), null));
    }

    /** 수식이 없는 합계 열을 추정한 항목 합으로 검산. 대부분 행이 안 맞으면 추정이 틀린 것으로 보고 한 건으로 알린다 */
    private static void estimate(Long fileId, List<String> headers, List<ParsedTable.Row> rows,
                                 Map<String, ColumnRule> formulaColumns, int target, Map<Integer, Integer> terms, String what,
                                 List<ValidationIssue> issues) {
        String column = headers.get(target);
        if (formulaColumns.containsKey(column) || rows.stream().anyMatch(r -> r.formulas().containsKey(column))) {
            return;
        }
        String formula = terms.entrySet().stream()
                .map(t -> (t.getValue() < 0 ? "-" : "+") + "{" + headers.get(t.getKey()) + "}")
                .collect(Collectors.joining()).replaceFirst("^\\+", "=");
        // 추정한 항목이 모두 빈 행은 추정 밖의 항목(강의료 등)으로 받은 것이라 검산하지 않는다
        List<ParsedTable.Row> covered = rows.stream().filter(r -> terms.keySet().stream()
                .anyMatch(c -> number(r.values().get(headers.get(c))).map(v -> v.signum() != 0).orElse(false))).toList();
        List<Mismatch> found = mismatches(headers, covered, target, terms);
        long checked = covered.stream().filter(r -> number(r.values().get(column)).isPresent()).count();
        long explained = found.stream().filter(m -> m.explainedBy() != null).count();
        if (found.size() * 2 > checked && explained * 2 < found.size()) {
            issues.add(new ValidationIssue(fileId, null, null, column, Severity.WARN, IssueCode.RECALC_MISMATCH,
                    "'" + column + "'이(가) 열 이름으로 추정한 " + what + " 합(" + formula + ")과 " + found.size() + "/" + checked
                            + "행에서 맞지 않습니다. 추정에 빠지거나 더 들어간 항목이 있는지 확인해 주세요", formula, null));
            return;
        }
        for (Mismatch m : found) {
            issues.add(rowIssue(fileId, m, column, "열 이름으로 추정한 " + what + " 합 " + formula));
        }
    }

    private static ValidationIssue rowIssue(Long fileId, Mismatch m, String column, String basis) {
        String message = "'" + column + "' 값 " + plain(m.actual()) + " ≠ " + basis + "로 다시 계산한 값 " + plain(m.expected())
                + " (차이 " + plain(m.diff().abs()) + ")"
                + (m.explainedBy() == null ? "" : ". 차이가 '" + m.explainedBy() + "' 값과 같습니다. 합에 빠졌거나 두 번 들어갔을 수 있습니다");
        return new ValidationIssue(fileId, m.rowNo(), null, column, Severity.WARN, IssueCode.RECALC_MISMATCH, message,
                m.actual().stripTrailingZeros().toPlainString(), m.expected().stripTrailingZeros().toPlainString());
    }

    private static String plain(BigDecimal v) {
        return new DecimalFormat("#,##0.##").format(v);
    }

    private static Map<Integer, Integer> plus(List<Integer> columns) {
        Map<Integer, Integer> m = new LinkedHashMap<>();
        columns.forEach(c -> m.put(c, 1));
        return m;
    }

    private static boolean hasSubtotal(List<String> headers, int from, int to) {
        return headers.subList(from, to).stream().map(PayrollColumns::normalize).anyMatch(h -> SUBTOTAL.matcher(h).matches());
    }

    /** [from, to) 안의 금액 열. 지급 쪽은 이름이 지급 항목 같은 열만, 공제 쪽은 시간·요율·과세표준이 아닌 숫자 열 전부 */
    private static List<Integer> amountColumns(List<String> headers, List<ParsedTable.Row> rows, int from, int to, boolean payOnly) {
        List<Integer> out = new ArrayList<>();
        for (int c = from; c < to; c++) {
            String h = normalize(headers.get(c));
            if (NOT_AMOUNT.matcher(h).find() || (payOnly && !PAY_ITEM.matcher(h).find())
                    || SUBTOTAL.matcher(h).matches() || GROSS.matcher(h).matches() || NET.matcher(h).matches()) {
                continue;
            }
            if (numeric(rows, headers.get(c))) {
                out.add(c);
            }
        }
        return out;
    }
}
