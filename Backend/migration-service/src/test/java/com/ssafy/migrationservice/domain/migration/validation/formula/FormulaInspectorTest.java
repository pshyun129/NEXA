package com.ssafy.migrationservice.domain.migration.validation.formula;

import com.ssafy.migrationservice.domain.migration.reader.ParsedTable;
import com.ssafy.migrationservice.domain.migration.validation.formula.FormulaInspector.ColumnRule;
import com.ssafy.migrationservice.domain.migration.validation.formula.FormulaInspector.ColumnSummary;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FormulaInspectorTest {

    private static final List<String> HEADERS = List.of("기본급", "직책수당", "통상시급", "국민연금");

    @Test
    void 상대_행_참조만_행_차이로_바꾸고_고정_참조_다른_시트_문자열은_그대로_둔다() {
        assertThat(FormulaPattern.pattern("ROUNDDOWN((F5+G5)/설정!$B$9,0)", 5)).isEqualTo("ROUNDDOWN((F[0]+G[0])/설정!$B$9,0)");
        assertThat(FormulaPattern.pattern("IF(A12=A11,D11,\"\")", 12)).isEqualTo("IF(A[0]=A[-1],D[-1],\"\")");
        assertThat(FormulaPattern.pattern("SUMIFS(TIME!$C$2:$C$9,TIME!$A$2:$A$9,$A7,TIME!$B$2:$B$9,\"2026-10\")", 7))
                .isEqualTo("SUMIFS(TIME!$C$2:$C$9,TIME!$A$2:$A$9,$A[0],TIME!$B$2:$B$9,\"2026-10\")");
        assertThat(FormulaPattern.pattern("LOG10(B3)", 3)).isEqualTo("LOG10(B[0])");
    }

    @Test
    void 서명은_조회_열번호_월_문자열은_같게_보고_참조_대신_숫자는_다르게_본다() {
        String july = FormulaPattern.pattern("VLOOKUP($A5,근태!$A$5:$AA$118,4,FALSE)", 5);
        String august = FormulaPattern.pattern("VLOOKUP($A5,근태!$A$5:$AA$118,8,FALSE)", 5);
        assertThat(FormulaPattern.signature(july)).isEqualTo(FormulaPattern.signature(august));

        String stale = FormulaPattern.pattern("ROUNDDOWN(R5*0.045,-1)", 5);
        String current = FormulaPattern.pattern("ROUNDDOWN(R5*설정!$B$4,-1)", 5);
        assertThat(FormulaPattern.signature(stale)).isNotEqualTo(FormulaPattern.signature(current));
    }

    @Test
    void 참조하는_시트만_다른_행은_같은_규칙으로_보고_계산이_다른_행만_다르다고_본다() {
        List<String> headers = List.of("부서", "지급계");
        List<ParsedTable.Row> rows = new ArrayList<>();
        String[] sheets = {"'프레스팀'!U89", "'가공1팀'!U71", "'도장팀'!U70", "'조립팀'!U68", "'물류팀'!U50"};
        for (int i = 0; i < sheets.length; i++) {
            rows.add(new ParsedTable.Row(i + 2, Map.of("부서", "x", "지급계", "1"), Map.of("지급계", sheets[i]), Map.of()));
        }
        rows.add(new ParsedTable.Row(7, Map.of("부서", "x", "지급계", "1"), Map.of("지급계", "'영업팀'!U40*2"), Map.of()));

        ColumnRule rule = FormulaInspector.inspect(headers, rows, List.of()).get(0);

        assertThat(rule.otherRows()).containsOnlyKeys(7);

        // 시트 참조가 아닌 숫자 차이(작년 기준일)는 계속 다른 규칙으로 본다
        assertThat(FormulaPattern.loose(FormulaPattern.pattern("DATE(2025,12,31)-D5+1", 5)))
                .isNotEqualTo(FormulaPattern.loose(FormulaPattern.pattern("DATE(2026,12,31)-D5+1", 5)));
    }

    @Test
    void 읽기_좋은_규칙은_헤더_이름으로_쓴다() {
        String p = FormulaPattern.pattern("ROUNDDOWN((A5+B5)/209,0)", 5);
        assertThat(FormulaPattern.readable(p, HEADERS)).isEqualTo("=ROUNDDOWN(({기본급}+{직책수당})/209,0)");
    }

    @Test
    void 수식_열에서_값_직접_입력_빈칸_다른_수식_행을_찾는다() {
        List<ParsedTable.Row> rows = new ArrayList<>();
        for (int r = 2; r <= 21; r++) {
            rows.add(row(r, "C", "ROUNDDOWN((A" + r + "+B" + r + ")/209,0)"));
        }
        rows.set(3, row(5, "C", null, "14354"));                       // 숫자 직접 입력
        rows.set(4, row(6, "C", null, null));                          // 수식 빠진 새 행
        rows.set(5, row(7, "C", "ROUND((A7+B7)/209,0)"));              // 다른 수식

        ColumnRule rule = FormulaInspector.inspect(HEADERS, rows, List.of()).get(0);

        assertThat(rule.column()).isEqualTo("통상시급");
        assertThat(rule.formulaColumn()).isTrue();
        assertThat(rule.hardcodedRows()).containsOnlyKeys(5);
        assertThat(rule.blankRows()).containsExactly(6);
        assertThat(rule.otherRows()).containsOnlyKeys(7);
    }

    @Test
    void 둘로_갈린_열은_다른_시트의_대표_수식을_따른다() {
        List<ParsedTable.Row> rows = new ArrayList<>();
        for (int r = 2; r <= 11; r++) {
            String range = r <= 6 ? "A" + r + ":D" + r : "A" + r + ":C" + r;   // 아래쪽 절반은 범위가 짧음
            rows.add(row(r, "D", "SUM(" + range + ")"));
        }
        String good = FormulaPattern.pattern("SUM(A2:D2)", 2);
        Map<String, ColumnSummary> otherSheet = Map.of("국민연금", new ColumnSummary(good, FormulaPattern.signature(good)));

        ColumnRule rule = FormulaInspector.inspect(HEADERS, rows, List.of(otherSheet)).get(0);

        assertThat(rule.pattern()).isEqualTo(good);
        assertThat(rule.otherRows()).containsOnlyKeys(7, 8, 9, 10, 11);
    }

    private static ParsedTable.Row row(int rowNo, String col, String formula) {
        return row(rowNo, col, formula, formula == null ? null : "1");
    }

    private static ParsedTable.Row row(int rowNo, String col, String formula, String value) {
        String header = HEADERS.get(FormulaPattern.columnIndex(col));
        Map<String, String> values = new LinkedHashMap<>();
        HEADERS.forEach(h -> values.put(h, "1"));
        values.put(header, value);
        Map<String, String> formulas = new HashMap<>();
        if (formula != null) {
            formulas.put(header, formula);
        }
        return new ParsedTable.Row(rowNo, values, formulas, Map.of());
    }
}
