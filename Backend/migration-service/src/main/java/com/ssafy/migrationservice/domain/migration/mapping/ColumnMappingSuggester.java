package com.ssafy.migrationservice.domain.migration.mapping;

import com.ssafy.migrationservice.domain.migration.validation.schema.EntitySchema;
import com.ssafy.migrationservice.domain.migration.validation.schema.FieldSpec;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 원본 헤더 → 필드 자동 추천. 필드 이름·한글 라벨·동의어를 공백·기호를 뺀 형태로 비교한다.
 * 한 필드는 한 컬럼에만 연결 (먼저 나온 컬럼 우선).
 */
public final class ColumnMappingSuggester {

    private ColumnMappingSuggester() {
    }

    /** 결과 값이 null이면 연결할 필드를 못 찾은 컬럼 */
    public static Map<String, String> suggest(List<String> headers, EntitySchema schema) {
        Map<String, String> result = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        for (String header : headers) {
            String h = normalize(header);
            String match = null;
            for (FieldSpec f : schema.fields()) {
                if (!used.contains(f.name()) && matches(h, f)) {
                    match = f.name();
                    break;
                }
            }
            if (match != null) {
                used.add(match);
            }
            result.put(header, match);
        }
        return result;
    }

    private static boolean matches(String normalizedHeader, FieldSpec f) {
        if (normalizedHeader.equals(normalize(f.name())) || normalizedHeader.equals(normalize(f.label()))) {
            return true;
        }
        return f.synonyms().stream().anyMatch(s -> normalizedHeader.equals(normalize(s)));
    }

    public static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\s()\\[\\]_\\-./]", "");
    }
}
