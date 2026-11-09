package com.ssafy.migrationservice.domain.migration.validation.formula;

import com.ssafy.migrationservice.domain.migration.reader.ParsedTable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 같은 행 범위에서 글자 하나를 세는 COUNTIF(근무표의 =COUNTIF($D12:$AH12,"N")) 다시 세기.
 * 저장된 값(엑셀이 센 값)과 다르면 'N '처럼 공백·보이지 않는 글자가 붙은 칸이 있다는 뜻이다
 * (읽을 때 값의 앞뒤 공백을 지우므로 다시 세면 그 칸도 들어간다). 와일드카드·비교 조건은 다루지 않는다.
 */
public final class Countif {

    private static final Pattern SAME_ROW = Pattern.compile(
            "COUNTIF\\(\\$?([A-Z]{1,3})\\$?(\\d+):\\$?([A-Z]{1,3})\\$?(\\d+),\"([^\"*?<>=~]+)\"\\)");

    private Countif() {
    }

    /** 이 행의 칸 수식이 같은 행 범위의 COUNTIF면 다시 센 값, 아니면 empty */
    public static Optional<BigDecimal> recount(List<String> headers, ParsedTable.Row row, String header) {
        String formula = row.formulas().get(header);
        Matcher m = formula == null ? null : SAME_ROW.matcher(formula.replace(" ", ""));
        if (m == null || !m.matches() || Integer.parseInt(m.group(2)) != row.rowNo() || Integer.parseInt(m.group(4)) != row.rowNo()) {
            return Optional.empty();
        }
        int from = FormulaPattern.columnIndex(m.group(1));
        int to = FormulaPattern.columnIndex(m.group(3));
        if (to >= headers.size()) {
            return Optional.empty();
        }
        long n = 0;
        for (int c = from; c <= to; c++) {
            String v = row.values().get(headers.get(c));
            if (v != null && v.strip().equalsIgnoreCase(m.group(5))) {     // COUNTIF는 대소문자를 가리지 않는다
                n++;
            }
        }
        return Optional.of(BigDecimal.valueOf(n));
    }
}
