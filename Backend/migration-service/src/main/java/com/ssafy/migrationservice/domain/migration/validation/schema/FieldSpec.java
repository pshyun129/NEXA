package com.ssafy.migrationservice.domain.migration.validation.schema;

import java.util.List;
import java.util.Map;

/**
 * 이관 대상 필드 정의.
 * synonyms: 원본 엑셀에서 흔히 쓰는 컬럼명 (자동 매핑에 사용)
 * enumValues: 허용 값 → 저장 코드 (한글 표기도 받는다. 예: "정규직" → REGULAR)
 * codeLike: 정해진 몇 가지 값만 쓰는 필드 (직위·직급 …). 드문 값이 흔한 값과 한 글자(자모)만 다르면 오타로 의심
 */
public record FieldSpec(
        String name,
        String label,
        FieldType type,
        boolean required,
        boolean key,
        Integer maxLength,
        List<String> synonyms,
        Map<String, String> enumValues,
        boolean codeLike
) {

    static Builder of(String name, String label, FieldType type) {
        return new Builder(name, label, type);
    }

    static final class Builder {
        private final String name;
        private final String label;
        private final FieldType type;
        private boolean required;
        private boolean key;
        private Integer maxLength;
        private List<String> synonyms = List.of();
        private Map<String, String> enumValues = Map.of();
        private boolean codeLike;

        private Builder(String name, String label, FieldType type) {
            this.name = name;
            this.label = label;
            this.type = type;
        }

        Builder required() {
            this.required = true;
            return this;
        }

        /** 기준 키: 필수 + 파일 안에서 유일해야 함 */
        Builder key() {
            this.key = true;
            this.required = true;
            return this;
        }

        Builder maxLength(int maxLength) {
            this.maxLength = maxLength;
            return this;
        }

        Builder synonyms(String... synonyms) {
            this.synonyms = List.of(synonyms);
            return this;
        }

        Builder enumValues(Map<String, String> enumValues) {
            this.enumValues = enumValues;
            return this;
        }

        Builder codeLike() {
            this.codeLike = true;
            return this;
        }

        FieldSpec build() {
            return new FieldSpec(name, label, type, required, key, maxLength, synonyms, enumValues, codeLike);
        }
    }
}
