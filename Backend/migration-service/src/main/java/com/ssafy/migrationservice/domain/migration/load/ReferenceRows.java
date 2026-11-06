package com.ssafy.migrationservice.domain.migration.load;

import com.ssafy.migrationservice.domain.migration.reader.ParsedTable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 사원 파일에서 기준정보(직위·직급·직책·직무 코드) 행을 뽑는다. 회사마다 체계가 달라 사원보다 먼저 대상에 넣는다.
 * 값마다 한 행 (같은 값은 한 번만). 행 번호는 원본 행이 아니라 이 목록의 순번이다.
 * - 직위 서열: 흔한 직위 순서로 매기고, 모르는 직위는 그 뒤에 처음 나온 순서대로 붙인다
 * - 직급: 'G3 2호봉'·'G3-2'처럼 호봉이 붙어 있으면 이름과 호봉으로 나눈다 (user-service 사원 적재와 같은 규칙)
 * 대상에 이미 있는 값은 대상이 그대로 둔다 (서열도 바꾸지 않는다).
 */
public final class ReferenceRows {

    // user-service 개발 시드의 서열과 같은 순서 (작을수록 아래)
    static final List<String> POSITION_ORDER = List.of(
            "사원", "주임", "대리", "과장", "차장", "부장", "이사", "상무", "전무", "부사장", "사장", "대표이사");
    // user-service MigrationReferenceLookup.GRADE_WITH_STEP 과 같게: "G3 2호봉", "G3-2", "G3(2호봉)", "G3 2"
    private static final Pattern GRADE_WITH_STEP = Pattern.compile("^(.+?)[\\s\\-_/(]+(\\d+)\\s*(?:호봉)?\\)?$");

    public static final String POSITION = "POSITION";
    public static final String GRADE = "GRADE";
    public static final String DUTY = "DUTY";
    public static final String JOB_CODE = "JOB_CODE";

    private ReferenceRows() {
    }

    /** fieldToColumn: 사원 파일의 필드 → 원본 컬럼 */
    public static List<LoadRow> from(Map<String, String> fieldToColumn, List<ParsedTable.Row> rows) {
        Set<String> positions = distinct(rows, fieldToColumn.get("position"));
        Set<String> grades = distinct(rows, fieldToColumn.get("grade"));
        Set<String> duties = distinct(rows, fieldToColumn.get("duty"));
        Set<List<String>> jobs = new LinkedHashSet<>();
        String family = fieldToColumn.get("jobFamily");
        String kind = fieldToColumn.get("jobKind");
        String role = fieldToColumn.get("jobRole");
        if (family != null && kind != null && role != null) {
            for (ParsedTable.Row r : rows) {
                String f = trim(r.values().get(family));
                String k = trim(r.values().get(kind));
                String ro = trim(r.values().get(role));
                if (f != null && k != null && ro != null) {
                    jobs.add(List.of(f, k, ro));
                }
            }
        }

        List<Map<String, Object>> data = new ArrayList<>();
        Map<String, Integer> ranks = positionRanks(positions);
        positions.forEach(p -> data.add(row(POSITION, "name", p, "rankOrder", ranks.get(p))));
        for (String g : grades) {
            Grade parsed = parseGrade(g);
            data.add(row(GRADE, "name", parsed.name(), "level", parsed.level()));
        }
        duties.forEach(d -> data.add(row(DUTY, "name", d)));
        jobs.forEach(j -> data.add(row(JOB_CODE, "family", j.get(0), "kind", j.get(1), "role", j.get(2))));

        List<LoadRow> out = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            out.add(new LoadRow(i + 1, data.get(i)));
        }
        return out;
    }

    public record Grade(String name, Integer level) {
    }

    public static Grade parseGrade(String value) {
        Matcher m = GRADE_WITH_STEP.matcher(value);
        return m.matches() ? new Grade(m.group(1).trim(), Integer.parseInt(m.group(2))) : new Grade(value, null);
    }

    /** 아는 직위는 정해진 서열, 모르는 직위는 그 뒤로 나온 순서대로 */
    static Map<String, Integer> positionRanks(Set<String> positions) {
        Map<String, Integer> ranks = new LinkedHashMap<>();
        int next = POSITION_ORDER.size() + 1;
        for (String p : positions) {
            int i = POSITION_ORDER.indexOf(p);
            ranks.put(p, i >= 0 ? i + 1 : next++);
        }
        return ranks;
    }

    private static Set<String> distinct(List<ParsedTable.Row> rows, String column) {
        Set<String> out = new LinkedHashSet<>();
        if (column != null) {
            for (ParsedTable.Row r : rows) {
                String v = trim(r.values().get(column));
                if (v != null) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    private static Map<String, Object> row(String type, Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
