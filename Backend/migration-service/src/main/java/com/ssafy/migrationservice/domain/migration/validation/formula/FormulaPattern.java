package com.ssafy.migrationservice.domain.migration.validation.formula;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 셀 수식을 '행과 무관한 모양'으로 바꾼다. 같은 열의 수식이 같은 규칙인지 비교하려고 쓴다.
 * - pattern: 같은 시트의 상대 행 참조를 행 차이로 (F5 → F[0], 위 행 D4 → D[-1]). $행 참조·다른 시트 참조·이름 정의는 그대로
 * - signature: pattern에서 숫자·문자열 상수와 다른 시트 참조 위치를 지운 것. 월별 시트처럼 조회 열 번호·월 문자열만
 *   다른 수식을 같은 규칙으로 보고, '셀 참조 대신 숫자'(작년 요율 0.045)나 '범위 끝 열'(SUM 범위 누락)은 다르게 본다
 */
public final class FormulaPattern {

    private static final Pattern STRING = Pattern.compile("\"[^\"]*\"");
    private static final Pattern REF = Pattern.compile(
            "(?<![A-Za-z0-9_.$!\\[])((?:'[^']+'|[A-Za-z0-9_가-힣.]+)!)?(\\$?)([A-Z]{1,3})(\\$?)(\\d+)(?![A-Za-z0-9_(\\[])");
    private static final Pattern SHEET_REF = Pattern.compile(
            "((?:'[^']+'|[A-Za-z0-9_가-힣.]+)!)\\$?[A-Z]{1,3}\\$?\\d+(?::\\$?[A-Z]{1,3}\\$?\\d+)?");
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z$\\[\\d.-])\\d+(\\.\\d+)?(?![\\d\\]])");
    private static final Pattern RELATIVE = Pattern.compile("(\\$?)([A-Z]{1,3})\\[(-?\\d+)]");
    // 다른 시트 참조 (범위 끝이 상대 행 [n]으로 바뀐 것 포함)
    private static final Pattern ANY_SHEET_REF = Pattern.compile(
            "(?:'[^']+'|[A-Za-z0-9_가-힣.]+)!\\$?[A-Z]{1,3}\\$?\\d+(?::\\$?[A-Z]{1,3}(?:\\$?\\d+|\\[-?\\d+]))?");

    private FormulaPattern() {
    }

    public static String pattern(String formula, int rowNo) {
        return outsideStrings(formula, part -> {
            Matcher m = REF.matcher(part);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                boolean otherSheet = m.group(1) != null;
                boolean absoluteRow = !m.group(4).isEmpty();
                String replacement = otherSheet || absoluteRow ? m.group()
                        : m.group(2) + m.group(3) + "[" + (Integer.parseInt(m.group(5)) - rowNo) + "]";
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            m.appendTail(sb);
            return sb.toString();
        });
    }

    public static String signature(String pattern) {
        String s = STRING.matcher(pattern).replaceAll("\"\"");
        s = SHEET_REF.matcher(s).replaceAll(r -> Matcher.quoteReplacement(r.group(1) + "REF"));
        return NUMBER.matcher(s).replaceAll("#");
    }

    /**
     * 어느 시트를 참조하는지까지 지운 서명. 총괄 시트의 부서별 행(='가공1팀'!U71, ='프레스팀'!U89)이나
     * 사람마다 소속 시트를 찾는 SUMIFS처럼, 행마다 참조 시트만 다른 것은 같은 규칙으로 본다
     */
    public static String loose(String pattern) {
        return ANY_SHEET_REF.matcher(pattern).replaceAll("REF");     // 숫자·문자열은 그대로 (작년 기준일 DATE(2025,…) 같은 차이는 잡아야 함)
    }

    /** 사람이 읽는 규칙: 같은 행 참조는 {헤더}, 다른 행은 {헤더}[-1] */
    public static String readable(String pattern, List<String> headers) {
        Matcher m = RELATIVE.matcher(pattern);
        StringBuilder sb = new StringBuilder("=");
        while (m.find()) {
            int col = columnIndex(m.group(2));
            String name = col < headers.size() ? "{" + headers.get(col) + "}" : m.group(2);
            int offset = Integer.parseInt(m.group(3));
            m.appendReplacement(sb, Matcher.quoteReplacement(offset == 0 ? name : name + "[" + offset + "]"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static int columnIndex(String letters) {
        int n = 0;
        for (char c : letters.toCharArray()) {
            n = n * 26 + (c - 'A' + 1);
        }
        return n - 1;
    }

    private static String outsideStrings(String formula, java.util.function.UnaryOperator<String> f) {
        Matcher m = STRING.matcher(formula);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(f.apply(formula.substring(last, m.start()))).append(m.group());
            last = m.end();
        }
        return sb.append(f.apply(formula.substring(last))).toString();
    }
}
