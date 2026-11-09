package com.ssafy.migrationservice.domain.migration.load;

import com.ssafy.migrationservice.domain.migration.reader.ParsedTable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 사원 행의 직급 → NEXA 직급(이름 + 호봉). NEXA 직급은 호봉이 꼭 있다.
 * - 직급 값이 'G3 2호봉'·'G3-2'면 이름·호봉으로 나누고, 호봉 열이 따로 있으면 합친다
 * - 직급 열이 없으면 직위를 직급으로 쓴다
 * - 호봉을 모르면 그 직급의 알려진 호봉(대상에 있는 것 + 같은 파일에 적힌 것)을 본다:
 *   없으면 1호봉(defaulted), 하나면 그 호봉, 여럿이면 정할 수 없음(ambiguous, 적재 막음)
 * 기준정보 묶음을 넣기 전·후에 같은 결과가 나오도록 대상에 있는 호봉과 파일의 호봉을 함께 본다.
 */
public final class GradeResolver {

    public record Grade(String name, Integer level, boolean defaulted, boolean ambiguous) {

        /** user-service 사원 적재가 읽는 표기 ('G3 2호봉'). 정할 수 없으면 이름만 */
        public String text() {
            return level == null ? name : name + " " + level + "호봉";
        }
    }

    private final Map<String, Set<Integer>> knownLevels;

    private GradeResolver(Map<String, Set<Integer>> knownLevels) {
        this.knownLevels = knownLevels;
    }

    /** existingLevels: 대상에 있는 직급 이름 → 호봉들 */
    public static GradeResolver of(Map<String, Set<Integer>> existingLevels, Map<String, String> fieldToColumn,
                                   List<ParsedTable.Row> rows) {
        Map<String, Set<Integer>> known = new HashMap<>();
        existingLevels.forEach((name, levels) -> levels.stream().filter(Objects::nonNull)
                .forEach(l -> known.computeIfAbsent(name, k -> new HashSet<>()).add(l)));
        for (ParsedTable.Row r : rows) {
            Raw raw = raw(fieldToColumn, r);
            if (raw != null && raw.level() != null) {
                known.computeIfAbsent(raw.name(), k -> new HashSet<>()).add(raw.level());
            }
        }
        return new GradeResolver(known);
    }

    /** 직급(없으면 직위) 값이 없으면 null */
    public Grade resolve(Map<String, String> fieldToColumn, ParsedTable.Row row) {
        Raw raw = raw(fieldToColumn, row);
        if (raw == null) {
            return null;
        }
        if (raw.level() != null) {
            return new Grade(raw.name(), raw.level(), false, false);
        }
        Set<Integer> levels = knownLevels.getOrDefault(raw.name(), Set.of());
        if (levels.isEmpty()) {
            return new Grade(raw.name(), 1, true, false);
        }
        if (levels.size() == 1) {
            return new Grade(raw.name(), levels.iterator().next(), false, false);
        }
        return new Grade(raw.name(), null, false, true);
    }

    /** 파일에 적힌 그대로의 직급 이름·호봉 (호봉을 모르면 level null) */
    public record Raw(String name, Integer level) {
    }

    public static Raw raw(Map<String, String> fieldToColumn, ParsedTable.Row row) {
        String column = fieldToColumn.containsKey("grade") ? fieldToColumn.get("grade") : fieldToColumn.get("position");
        String value = column == null ? null : trim(row.values().get(column));
        if (value == null) {
            return null;
        }
        ReferenceRows.Grade parsed = ReferenceRows.parseGrade(value);
        String stepColumn = fieldToColumn.get("gradeStep");
        Integer step = stepColumn == null ? null : step(row.values().get(stepColumn));
        return new Raw(parsed.name(), step != null ? step : parsed.level());
    }

    /** '27', '27호봉', '27.0' → 27. 1 이상 정수가 아니면 null */
    static Integer step(String value) {
        String v = trim(value);
        if (v == null) {
            return null;
        }
        v = v.replace("호봉", "").trim();
        if (v.endsWith(".0")) {
            v = v.substring(0, v.length() - 2);
        }
        if (!v.matches("\\d{1,4}")) {
            return null;
        }
        int n = Integer.parseInt(v);
        return n >= 1 ? n : null;
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
