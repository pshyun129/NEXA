package com.ssafy.migrationservice.domain.migration.validation;

import com.ssafy.migrationservice.domain.migration.reader.ParsedTable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 코드성 필드(직위·직급 …)의 오타 의심: 드물게 나온 값이 흔한 값과 한글 자모 하나만 다르면 그 흔한 값의 오타로 본다.
 * 자모로 비교하므로 '과정→과장', '데리→대리'는 잡고, 실제로 다른 직위인 '부장↔차장'(초성·중성 둘 다 다름)은 잡지 않는다.
 * 한글이 없는 값(G1·G2 같은 코드)은 비교하지 않는다. 대상 서비스의 코드 목록과 대조하는 것은 코드 매핑 단계의 일.
 */
final class CodeValueChecker {

    private static final int RARE_MAX = 2;          // 이 횟수 이하로 나온 값만 오타 후보
    private static final int COMMON_MIN = 3;        // 비교 대상은 이 횟수 이상
    private static final int COMMON_RATIO = 3;      // 그리고 후보보다 이 배 이상 많이 나옴

    private CodeValueChecker() {
    }

    record Suspect(String value, String likely, long likelyCount) {
    }

    /** 값 → 오타 의심 (그 값이 나온 행마다 같은 결과) */
    static Map<String, Suspect> suspects(List<ParsedTable.Row> rows, String column) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ParsedTable.Row row : rows) {
            String v = row.values().get(column);
            if (v != null) {
                counts.merge(v, 1L, Long::sum);
            }
        }
        Map<String, Suspect> result = new LinkedHashMap<>();
        counts.forEach((value, n) -> {
            if (n > RARE_MAX || !hasHangul(value)) {
                return;
            }
            closest(value, n, counts).ifPresent(s -> result.put(value, s));
        });
        return result;
    }

    private static Optional<Suspect> closest(String value, long n, Map<String, Long> counts) {
        List<Suspect> hits = new ArrayList<>();
        String jv = jamo(value);
        counts.forEach((other, m) -> {
            if (!other.equals(value) && m >= COMMON_MIN && m >= n * COMMON_RATIO && hasHangul(other)
                    && editDistance(jv, jamo(other)) == 1) {
                hits.add(new Suspect(value, other, m));
            }
        });
        return hits.size() == 1 ? Optional.of(hits.get(0)) : Optional.empty();   // 후보가 여럿이면 판단하지 않음
    }

    private static boolean hasHangul(String s) {
        return s.chars().anyMatch(c -> c >= 0xAC00 && c <= 0xD7A3);
    }

    /** 한글 음절을 초성·중성·종성 세 글자로 펼친다 (그 밖의 글자는 그대로) */
    static String jamo(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= 0xAC00 && c <= 0xD7A3) {
                int i = c - 0xAC00;
                sb.append((char) (0x1100 + i / 588)).append((char) (0x1161 + i % 588 / 28)).append((char) (0x11A7 + i % 28));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int sub = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                cur[j] = Math.min(sub, Math.min(prev[j], cur[j - 1]) + 1);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
