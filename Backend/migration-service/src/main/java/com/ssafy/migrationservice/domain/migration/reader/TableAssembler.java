package com.ssafy.migrationservice.domain.migration.reader;

import com.ssafy.migrationservice.global.exception.BusinessException;
import com.ssafy.migrationservice.global.exception.ErrorCode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 원시 행 목록 → 헤더 + 데이터 행. 엑셀·CSV 공통 규칙을 한 곳에 둔다.
 * - 헤더: 위쪽 30행에서 '글자' 칸(숫자·날짜·시각이 아닌 값)이 가장 많은 행의 절반 이상인 첫 행.
 *   제목·작성일·요율·묶음 머리글 행은 글자 칸이 적어서 건너뛴다. 같은 글자는 두 번까지만 세서
 *   '○'를 날짜마다 찍은 데이터 행(근로내용확인신고)이 헤더보다 글자 칸이 많아지지 않게 한다
 * - 헤더 바로 위 행의 가로 병합(묶음 머리글)은, 헤더 이름이 겹치는 열에만 앞에 붙인다 (예: 07월 + 연장 → "07월 연장")
 * - 빈 헤더는 "컬럼N", 중복 헤더는 "이름_2" 식으로 바꿈
 * - 완전히 빈 행은 버리고, 합계·소계 행은 데이터에서 뺀다
 */
final class TableAssembler {

    private static final int MIN_HEADER_CELLS = 2;
    private static final int HEADER_SCAN_ROWS = 30;
    private static final Pattern NOT_TEXT = Pattern.compile(
            "-?[\\d,]+(\\.\\d+)?%?|\\d{4}[-./]\\d{1,2}[-./]\\d{1,2}.*|\\d{1,2}:\\d{2}(:\\d{2})?");
    private static final Pattern GRAND_TOTAL = Pattern.compile("합계|총계|총합계|total|grandtotal");
    private static final Pattern SUB_TOTAL = Pattern.compile("소계|계|subtotal");
    // '강남점 소계', 'A조 합계' 처럼 이름이 붙은 소계 (총계로 보지 않음)
    private static final Pattern NAMED_SUB_TOTAL = Pattern.compile(".{1,15}(소계|합계|총계)|.{1,15}(sub)?total");
    // 같은 열의 위쪽 행을 모으는 수식 (=SUM(F4:F25), 근무표 아래 =COUNTIF(D5:D24,"N")) — 이름이 없어도 이런 칸이 대부분이면 소계 행
    private static final Pattern VERTICAL_SUM = Pattern.compile(
            "(?:SUM|SUMIF|COUNT|COUNTA|COUNTIF|AVERAGE|MAX|MIN)\\(\\$?([A-Z]{1,3})\\$?(\\d+):\\$?([A-Z]{1,3})\\$?(\\d+)(?:,[^()]*)?\\)");
    private static final int TOTAL_LABEL_COLUMNS = 3;
    private static final int LABEL_ROW_MIN_COLUMNS = 5;

    private TableAssembler() {
    }

    static ParsedTable assemble(List<RawRow> rawRows, String sheetName) {
        int headerIdx = findHeaderIndex(rawRows);
        RawRow headerRow = rawRows.get(headerIdx);
        RawRow above = headerIdx > 0 ? rawRows.get(headerIdx - 1) : null;
        Map<Integer, String> groups = above != null && above.rowNo() == headerRow.rowNo() - 1 ? above.groups() : Map.of();
        List<String> headers = normalizeHeaders(headerRow.cells(), groups);

        List<ParsedTable.Row> rows = new ArrayList<>();
        List<Integer> totals = new ArrayList<>();
        List<Integer> afterTotal = new ArrayList<>();
        boolean pastGrandTotal = false;
        for (int i = headerIdx + 1; i < rawRows.size(); i++) {
            RawRow raw = rawRows.get(i);
            if (raw.isBlank()) {
                continue;
            }
            String total = totalLabel(raw);
            if (total != null) {
                totals.add(raw.rowNo());
                pastGrandTotal |= GRAND_TOTAL.matcher(total).matches();
                continue;
            }
            if (headers.size() >= LABEL_ROW_MIN_COLUMNS && isLabelRow(raw)) {
                continue;   // '■ 퍼허푸드 (PF)' 같은 구역 제목, '위 금액을 정히 영수함' 같은 맺음말: 데이터가 아님
            }
            if (pastGrandTotal) {
                afterTotal.add(raw.rowNo());
            }
            Map<String, String> values = new LinkedHashMap<>();
            Map<String, String> formulas = new HashMap<>();
            Map<String, String> errors = new HashMap<>();
            for (int c = 0; c < headers.size(); c++) {
                String h = headers.get(c);
                values.put(h, blankToNull(raw.cell(c)));
                if (raw.formula(c) != null) {
                    formulas.put(h, raw.formula(c));
                }
                if (raw.error(c) != null) {
                    errors.put(h, raw.error(c));
                }
            }
            rows.add(new ParsedTable.Row(raw.rowNo(), values, formulas, errors));
        }
        List<String> preamble = rawRows.subList(0, headerIdx).stream()
                .map(r -> String.join(" ", r.cells().stream().map(TableAssembler::blankToNull).filter(Objects::nonNull).toList()))
                .filter(s -> !s.isEmpty())
                .toList();
        return new ParsedTable(headers, rows, sheetName, headerRow.rowNo(), totals, afterTotal, preamble);
    }

    static ParsedTable assemble(List<RawRow> rawRows) {
        return assemble(rawRows, null);
    }

    private static int findHeaderIndex(List<RawRow> rawRows) {
        int scan = Math.min(rawRows.size(), HEADER_SCAN_ROWS);
        long max = 0;
        for (int i = 0; i < scan; i++) {
            max = Math.max(max, rawRows.get(i).headerScore());
        }
        // 글자 칸이 가장 많은 행의 절반 이상인 첫 행 (데이터 행이 헤더보다 글자 칸이 조금 많아도 헤더를 고른다)
        for (int i = 0; i < scan && max >= MIN_HEADER_CELLS; i++) {
            long score = rawRows.get(i).headerScore();
            if (score >= MIN_HEADER_CELLS && score * 2 >= max) {
                // 가로 병합된 묶음 머리글 행이면 바로 아래(글자 칸이 더 많은) 행이 진짜 헤더
                while (i + 1 < rawRows.size() && !rawRows.get(i).groups().isEmpty()
                        && rawRows.get(i + 1).textCount() > rawRows.get(i).textCount()) {
                    i++;
                }
                return i;
            }
        }
        // 글자 칸이 2개 넘는 행이 없으면 (숫자만 있는 표 등) 값이 2칸 이상인 첫 행
        for (int i = 0; i < rawRows.size(); i++) {
            if (rawRows.get(i).nonBlankCount() >= MIN_HEADER_CELLS) {
                return i;
            }
        }
        throw new BusinessException(ErrorCode.FILE_NO_HEADER);
    }

    private static List<String> normalizeHeaders(List<String> cells, Map<Integer, String> groups) {
        // 오른쪽 끝의 빈 칸은 컬럼으로 치지 않음
        int last = cells.size() - 1;
        while (last >= 0 && blankToNull(cells.get(last)) == null) {
            last--;
        }
        List<String> base = new ArrayList<>();
        Map<String, Integer> counts = new HashMap<>();
        for (int c = 0; c <= last; c++) {
            String h = blankToNull(cells.get(c));
            String name = h == null ? null : h.replaceAll("\\s+", " ");
            base.add(name);
            if (name != null) {
                counts.merge(name, 1, Integer::sum);
            }
        }
        List<String> headers = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int c = 0; c <= last; c++) {
            String name = base.get(c);
            String group = blankToNull(groups.get(c));
            if (name != null && counts.get(name) > 1 && group != null && !group.equals(name)) {
                name = group.replaceAll("\\s+", " ") + " " + name;
            }
            String b = name == null ? "컬럼" + (c + 1) : name;
            String unique = b;
            for (int n = 2; !seen.add(unique); n++) {
                unique = b + "_" + n;
            }
            headers.add(unique);
        }
        return headers;
    }

    /**
     * 합계·소계 행이면 그 글자 (총계면 '합계'처럼 GRAND_TOTAL에 맞는 글자), 아니면 null.
     * 앞쪽 몇 칸에 '합계'·'소계'·'강남점 소계' 같은 글자가 있거나, 수식 칸 대부분이 같은 열 위쪽 행을 더하는 행
     */
    private static String totalLabel(RawRow raw) {
        for (int c = 0; c < Math.min(TOTAL_LABEL_COLUMNS, raw.cells().size()); c++) {
            String v = blankToNull(raw.cell(c));
            if (v == null) {
                continue;
            }
            String t = v.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
            if (GRAND_TOTAL.matcher(t).matches() || SUB_TOTAL.matcher(t).matches() || NAMED_SUB_TOTAL.matcher(t).matches()) {
                return t;
            }
        }
        return isVerticalSumRow(raw) ? "소계" : null;
    }

    private static boolean isVerticalSumRow(RawRow raw) {
        int formulas = 0;
        int sums = 0;
        for (int c = 0; c < raw.cells().size(); c++) {
            String f = raw.formula(c);
            if (f == null) {
                continue;
            }
            formulas++;
            Matcher m = VERTICAL_SUM.matcher(f.replace(" ", ""));
            if (m.matches() && m.group(1).equals(m.group(3)) && Integer.parseInt(m.group(4)) < raw.rowNo()) {
                sums++;
            }
        }
        return sums >= 2 && sums * 2 >= formulas;
    }

    /** 글자 한 칸만 있고 수식이 없는 행 */
    private static boolean isLabelRow(RawRow raw) {
        return raw.nonBlankCount() == 1 && raw.textCount() == 1 && raw.formulas().stream().allMatch(f -> f == null);
    }

    static String blankToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.strip();
        return t.isEmpty() ? null : t;
    }

    /**
     * 파일 형식별 리더가 만드는 중간 결과. rowNo는 원본 기준 1부터.
     * formulas·errors는 셀 위치가 cells와 같은 목록 (CSV는 비어 있음), groups는 이 행의 가로 병합 열 → 머리글
     */
    record RawRow(int rowNo, List<String> cells, List<String> formulas, List<String> errors, Map<Integer, String> groups) {

        RawRow(int rowNo, List<String> cells) {
            this(rowNo, cells, List.of(), List.of(), Map.of());
        }

        String cell(int c) {
            return c < cells.size() ? cells.get(c) : null;
        }

        String formula(int c) {
            return c < formulas.size() ? formulas.get(c) : null;
        }

        String error(int c) {
            return c < errors.size() ? errors.get(c) : null;
        }

        boolean isBlank() {
            return nonBlankCount() == 0 && errors.stream().allMatch(e -> e == null);
        }

        long nonBlankCount() {
            return cells.stream().filter(v -> blankToNull(v) != null).count();
        }

        long textCount() {
            return cells.stream().map(TableAssembler::blankToNull)
                    .filter(v -> v != null && !NOT_TEXT.matcher(v).matches())
                    .count();
        }

        /** 글자 칸 수. 같은 글자는 두 번까지만 센다 (헤더의 '연장'·'야간' 반복은 세고, 날짜마다 찍은 '○'는 한 번으로) */
        long headerScore() {
            return cells.stream().map(TableAssembler::blankToNull)
                    .filter(v -> v != null && !NOT_TEXT.matcher(v).matches())
                    .collect(Collectors.groupingBy(v -> v, Collectors.counting()))
                    .values().stream().mapToLong(n -> Math.min(n, 2)).sum();
        }
    }
}
