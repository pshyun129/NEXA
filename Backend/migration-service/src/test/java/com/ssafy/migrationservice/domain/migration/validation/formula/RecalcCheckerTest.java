package com.ssafy.migrationservice.domain.migration.validation.formula;

import com.ssafy.migrationservice.domain.migration.reader.ParsedTable;
import com.ssafy.migrationservice.domain.migration.validation.issue.IssueCode;
import com.ssafy.migrationservice.domain.migration.validation.issue.ValidationIssue;
import com.ssafy.migrationservice.domain.migration.validation.payroll.PayrollColumns;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecalcCheckerTest {

    // A 성명, B 기본급, C 식대, D 연장 시간, E 연장수당, F 휴일수당, G 지급계, H 국민연금, I 소득세, J 공제계, K 실지급액
    private static final List<String> HEADERS =
            List.of("성명", "기본급", "식대", "연장 시간", "연장수당", "휴일수당", "지급계", "국민연금", "소득세", "공제계", "실지급액");

    @Test
    void 덧셈_뺄셈_SUM만_있는_수식은_열별_계수로_바꾸고_나머지는_다시_계산하지_않는다() {
        assertThat(RecalcChecker.linear("SUM(B[0]:C[0],E[0]:F[0])")).isEqualTo(Map.of(1, 1, 2, 1, 4, 1, 5, 1));
        assertThat(RecalcChecker.linear("G[0]-J[0]")).containsEntry(6, 1).containsEntry(9, -1).hasSize(2);
        assertThat(RecalcChecker.linear("SUM(B[0]:C[0])+SUM(E[0]:F[0])")).hasSize(4);
        assertThat(RecalcChecker.linear("ROUND(G[0]*0.045,-1)")).isNull();
        assertThat(RecalcChecker.linear("SUM(B[-1]:B[0])")).isNull();        // 위 행 누계
        assertThat(RecalcChecker.linear("SUM(설정!$B$4,B[0])")).isNull();
    }

    @Test
    void 숫자는_쉼표와_원을_빼고_읽는다() {
        assertThat(PayrollColumns.number("2,156,880")).contains(new BigDecimal("2156880"));
        assertThat(PayrollColumns.number("10,320원")).contains(new BigDecimal("10320"));
        assertThat(PayrollColumns.number("-1500.5")).contains(new BigDecimal("-1500.5"));
        assertThat(PayrollColumns.number("팀장")).isEmpty();
    }

    @Test
    void 수식_열에_숫자로_넣은_합계가_틀리면_대표_수식으로_다시_계산한_금액을_알린다() {
        List<ParsedTable.Row> rows = sheet(10, true);
        // 수식을 지우고 틀린 금액 입력 → 실지급액 수식은 그 금액으로 다시 계산됨
        int ded = Integer.parseInt(rows.get(2).values().get("공제계"));
        rows.set(2, override(rows.get(2), Map.of("지급계", "3000000", "실지급액", String.valueOf(3_000_000 - ded)), "지급계"));

        List<ValidationIssue> issues = check(rows);

        ValidationIssue issue = only(issues, IssueCode.RECALC_MISMATCH);
        assertThat(issue.rowNo()).isEqualTo(4);
        assertThat(issue.sourceColumn()).isEqualTo("지급계");
        assertThat(issue.suggestion()).isEqualTo(String.valueOf(expectedGross(4)));
    }

    @Test
    void 일부_행만_SUM_범위가_짧으면_차이가_빠진_열_값과_같다고_알린다() {
        List<ParsedTable.Row> rows = sheet(10, true);
        ParsedTable.Row r = rows.get(5);
        int ded = Integer.parseInt(r.values().get("국민연금"));    // 소득세가 빠진 공제계
        Map<String, String> formulas = new HashMap<>(r.formulas());
        formulas.put("공제계", "SUM(H" + r.rowNo() + ":H" + r.rowNo() + ")");
        rows.set(5, new ParsedTable.Row(r.rowNo(), with(r.values(), "공제계", String.valueOf(ded)), formulas, Map.of()));

        List<ValidationIssue> issues = check(rows).stream().filter(i -> i.sourceColumn().equals("공제계")).toList();

        assertThat(issues).singleElement().satisfies(i -> {
            assertThat(i.code()).isEqualTo(IssueCode.RECALC_MISMATCH);
            assertThat(i.rowNo()).isEqualTo(7);
            assertThat(i.message()).contains("'소득세' 값과 같습니다");
        });
    }

    @Test
    void 지급계_수식_범위에_빠진_지급_항목_열을_시트_단위로_알린다() {
        List<ParsedTable.Row> rows = new ArrayList<>();
        for (ParsedTable.Row r : sheet(10, true)) {
            Map<String, String> formulas = new HashMap<>(r.formulas());
            int n = r.rowNo();
            formulas.put("지급계", "SUM(B" + n + ":C" + n + ",E" + n + ":E" + n + ")");   // 휴일수당 빠짐
            int gross = Integer.parseInt(r.values().get("지급계")) - Integer.parseInt(r.values().get("휴일수당"));
            rows.add(new ParsedTable.Row(n, with(r.values(), "지급계", String.valueOf(gross)), formulas, Map.of()));
        }

        List<ValidationIssue> issues = check(rows);

        ValidationIssue issue = only(issues, IssueCode.TOTAL_ITEM_MISSING);
        assertThat(issue.rowNo()).isNull();
        assertThat(issue.value()).isEqualTo("휴일수당");
        assertThat(issue.message()).contains("5행");    // 휴일수당이 있는 행 (짝수 행만)
        // 시간 열(연장 시간)은 지급 항목으로 보지 않는다
        assertThat(issue.message()).doesNotContain("연장 시간");
    }

    @Test
    void 수식이_없는_파일은_열_이름으로_항목을_추정해_검산한다() {
        List<ParsedTable.Row> rows = sheet(10, false);
        rows.set(3, override(rows.get(3), Map.of("실지급액", "1"), null));

        List<ValidationIssue> issues = check(rows);

        assertThat(issues).singleElement().satisfies(i -> {
            assertThat(i.code()).isEqualTo(IssueCode.RECALC_MISMATCH);
            assertThat(i.rowNo()).isEqualTo(5);
            assertThat(i.sourceColumn()).isEqualTo("실지급액");
            assertThat(i.message()).contains("{지급계}-{공제계}");
        });
    }

    @Test
    void 추정이_대부분_행에서_안_맞으면_행마다_알리지_않고_한_건으로_묶는다() {
        List<ParsedTable.Row> rows = new ArrayList<>();
        for (ParsedTable.Row r : sheet(10, false)) {      // 추정에 없는 수당이 지급계에 들어간 회사
            int gross = Integer.parseInt(r.values().get("지급계")) + 50_000;
            int net = Integer.parseInt(r.values().get("실지급액")) + 50_000;
            rows.add(override(r, Map.of("지급계", String.valueOf(gross), "실지급액", String.valueOf(net)), null));
        }

        List<ValidationIssue> issues = check(rows);

        assertThat(issues).singleElement().satisfies(i -> {
            assertThat(i.rowNo()).isNull();
            assertThat(i.sourceColumn()).isEqualTo("지급계");
            assertThat(i.message()).contains("10/10행");
        });
    }

    @Test
    void 대부분_행이_안_맞아도_차이가_한_항목_값으로_설명되면_행마다_알린다() {
        List<ParsedTable.Row> rows = new ArrayList<>();
        for (ParsedTable.Row r : sheet(10, false)) {      // 아래쪽 6행은 공제계에서 소득세가 빠짐
            if (r.rowNo() < 6) {
                rows.add(r);
                continue;
            }
            int ded = Integer.parseInt(r.values().get("국민연금"));
            int net = Integer.parseInt(r.values().get("지급계")) - ded;
            rows.add(override(r, Map.of("공제계", String.valueOf(ded), "실지급액", String.valueOf(net)), null));
        }

        List<ValidationIssue> issues = check(rows);

        assertThat(issues).hasSize(6).allSatisfy(i -> {
            assertThat(i.sourceColumn()).isEqualTo("공제계");
            assertThat(i.message()).contains("'소득세' 값과 같습니다");
        });
    }

    @Test
    void 추정한_지급_항목이_모두_빈_행은_검산하지_않는다() {
        List<ParsedTable.Row> rows = sheet(10, false);
        for (int i = 0; i < 4; i++) {      // 강사: 추정에 없는 강의료로만 받아 기본급·식대·수당 칸이 빈 행
            ParsedTable.Row r = rows.get(i);
            Map<String, String> v = new LinkedHashMap<>(r.values());
            for (String h : List.of("기본급", "식대", "연장수당", "휴일수당")) {
                v.put(h, null);
            }
            rows.set(i, new ParsedTable.Row(r.rowNo(), v, Map.of(), Map.of()));
        }

        assertThat(check(rows)).isEmpty();
    }

    @Test
    void 지급계가_수당계를_더하면_수당계가_더하는_열은_빠진_것으로_보지_않는다() {
        // A 기본급, B 연장수당, C 휴일수당, D 수당계, E 지급계
        List<String> headers = List.of("기본급", "연장수당", "휴일수당", "수당계", "지급계");
        List<ParsedTable.Row> withFormulas = new ArrayList<>();
        List<ParsedTable.Row> valuesOnly = new ArrayList<>();
        for (int n = 2; n <= 11; n++) {
            Map<String, String> v = new LinkedHashMap<>();
            v.put("기본급", String.valueOf(base(n)));
            v.put("연장수당", String.valueOf(n * 1_000));
            v.put("휴일수당", "30000");
            v.put("수당계", String.valueOf(n * 1_000 + 30_000));
            v.put("지급계", String.valueOf(base(n) + n * 1_000 + 30_000));
            withFormulas.add(new ParsedTable.Row(n, v, Map.of("수당계", "SUM(B" + n + ":C" + n + ")", "지급계", "A" + n + "+D" + n), Map.of()));
            valuesOnly.add(new ParsedTable.Row(n, v));
        }

        assertThat(RecalcChecker.check(1L, headers, withFormulas, FormulaInspector.inspect(headers, withFormulas, List.of()))).isEmpty();
        assertThat(RecalcChecker.check(1L, headers, valuesOnly, List.of())).isEmpty();
    }

    @Test
    void 정상_시트_빈칸_오류_행은_검산하지_않는다() {
        assertThat(check(sheet(10, true))).isEmpty();
        assertThat(check(sheet(10, false))).isEmpty();

        List<ParsedTable.Row> rows = sheet(10, true);
        ParsedTable.Row r = rows.get(1);
        Map<String, String> values = with(with(r.values(), "연장수당", null), "지급계", null);
        rows.set(1, new ParsedTable.Row(r.rowNo(), values, r.formulas(), Map.of("연장수당", "#REF!", "지급계", "#REF!")));
        ParsedTable.Row blank = rows.get(2);                 // 공제 수식을 빠뜨린 새 행: 빈칸은 0
        rows.set(2, new ParsedTable.Row(blank.rowNo(), with(with(with(blank.values(), "국민연금", null), "소득세", null),
                "공제계", "0"), blank.formulas(), Map.of()));
        rows.set(2, override(rows.get(2), Map.of("실지급액", rows.get(2).values().get("지급계")), null));

        assertThat(check(rows)).isEmpty();
    }

    // ---------- 데이터 ----------

    private static List<ValidationIssue> check(List<ParsedTable.Row> rows) {
        List<FormulaInspector.ColumnRule> rules = FormulaInspector.inspect(HEADERS, rows, List.of());
        return RecalcChecker.check(1L, HEADERS, rows, rules);
    }

    private static ValidationIssue only(List<ValidationIssue> issues, IssueCode code) {
        List<ValidationIssue> found = issues.stream().filter(i -> i.code() == code).toList();
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private static int base(int n) {
        return 2_500_000 + n * 10_000;
    }

    private static int expectedGross(int n) {
        return base(n) + 200_000 + n * 1_000 + (n % 2 == 0 ? 30_000 : 0);
    }

    /** 2행부터 n개 행. 짝수 행만 휴일수당이 있다 */
    private static List<ParsedTable.Row> sheet(int count, boolean withFormulas) {
        List<ParsedTable.Row> rows = new ArrayList<>();
        for (int n = 2; n < 2 + count; n++) {
            int gross = expectedGross(n);
            int np = gross / 20;
            int tax = gross / 50;
            Map<String, String> v = new LinkedHashMap<>();
            v.put("성명", "직원" + n);
            v.put("기본급", String.valueOf(base(n)));
            v.put("식대", "200000");
            v.put("연장 시간", String.valueOf(n));
            v.put("연장수당", String.valueOf(n * 1_000));
            v.put("휴일수당", n % 2 == 0 ? "30000" : "0");
            v.put("지급계", String.valueOf(gross));
            v.put("국민연금", String.valueOf(np));
            v.put("소득세", String.valueOf(tax));
            v.put("공제계", String.valueOf(np + tax));
            v.put("실지급액", String.valueOf(gross - np - tax));
            Map<String, String> f = new HashMap<>();
            if (withFormulas) {
                f.put("지급계", "SUM(B" + n + ":C" + n + ",E" + n + ":F" + n + ")");
                f.put("공제계", "SUM(H" + n + ":I" + n + ")");
                f.put("실지급액", "G" + n + "-J" + n);
            }
            rows.add(new ParsedTable.Row(n, v, f, Map.of()));
        }
        return rows;
    }

    private static ParsedTable.Row override(ParsedTable.Row r, Map<String, String> values, String dropFormula) {
        Map<String, String> v = new LinkedHashMap<>(r.values());
        v.putAll(values);
        Map<String, String> f = new HashMap<>(r.formulas());
        if (dropFormula != null) {
            f.remove(dropFormula);
        }
        return new ParsedTable.Row(r.rowNo(), v, f, r.errors());
    }

    private static Map<String, String> with(Map<String, String> values, String key, String value) {
        Map<String, String> v = new LinkedHashMap<>(values);
        v.put(key, value);
        return v;
    }
}
