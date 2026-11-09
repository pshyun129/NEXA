package com.ssafy.migrationservice.domain.migration.service;

import com.ssafy.migrationservice.domain.migration.dto.ColumnProfile;
import com.ssafy.migrationservice.domain.migration.reader.ParsedTable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 컬럼별 진단: 빈 값 비율, 고유값 수, 값 형태(날짜·숫자·문자), 날짜 표기 종류 */
final class ColumnProfiler {

    // 표기 이름 → 패턴. 순서대로 먼저 맞는 것으로 판정
    private static final Map<String, Pattern> DATE_FORMATS = new LinkedHashMap<>();

    static {
        DATE_FORMATS.put("YYYY-MM-DD", Pattern.compile("\\d{4}-\\d{1,2}-\\d{1,2}"));
        DATE_FORMATS.put("YYYY.MM.DD", Pattern.compile("\\d{4}\\.\\s?\\d{1,2}\\.\\s?\\d{1,2}\\.?"));
        DATE_FORMATS.put("YYYY/MM/DD", Pattern.compile("\\d{4}/\\d{1,2}/\\d{1,2}"));
        DATE_FORMATS.put("YYYYMMDD", Pattern.compile("(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])"));
    }

    private static final Pattern NUMBER = Pattern.compile("-?[\\d,]+(\\.\\d+)?");

    private ColumnProfiler() {
    }

    static List<ColumnProfile> profile(List<String> headers, List<ParsedTable.Row> rows) {
        List<ColumnProfile> result = new ArrayList<>();
        for (String header : headers) {
            int nulls = 0;
            Set<String> distinct = new HashSet<>();
            Set<String> dateFormats = new LinkedHashSet<>();
            boolean allDate = true;
            boolean allNumber = true;
            for (ParsedTable.Row row : rows) {
                String v = row.values().get(header);
                if (v == null) {
                    nulls++;
                    continue;
                }
                distinct.add(v);
                String fmt = dateFormat(v);
                if (fmt == null) {
                    allDate = false;
                } else {
                    dateFormats.add(fmt);
                }
                if (!NUMBER.matcher(v).matches()) {
                    allNumber = false;
                }
            }
            int filled = rows.size() - nulls;
            String type = filled == 0 ? "EMPTY" : allDate ? "DATE" : allNumber ? "NUMBER" : "STRING";
            double nullRate = rows.isEmpty() ? 0 : Math.round(nulls * 10000.0 / rows.size()) / 10000.0;
            result.add(new ColumnProfile(header, type, nullRate, distinct.size(),
                    "DATE".equals(type) ? List.copyOf(dateFormats) : List.of()));
        }
        return result;
    }

    static String dateFormat(String v) {
        for (Map.Entry<String, Pattern> e : DATE_FORMATS.entrySet()) {
            if (e.getValue().matcher(v).matches()) {
                return e.getKey();
            }
        }
        return null;
    }
}
