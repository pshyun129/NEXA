package com.ssafy.migrationservice.domain.migration.mapping;

import com.ssafy.migrationservice.domain.migration.validation.schema.EntitySchema;
import com.ssafy.migrationservice.domain.migration.validation.schema.FieldSpec;
import com.ssafy.migrationservice.global.llm.LlmClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 동의어로 못 찾은 컬럼을 LLM에게 물어 매핑을 추천받는다 (OpenAI Responses API 형식, 기본은 SSAFY GMS).
 * 보내는 것: 컬럼 이름, 값의 '모양'(숫자→9, 한글→가, 영문→a로 바꾼 예시 몇 개), 대상 필드 목록. 실제 값은 보내지 않는다.
 * 키가 없거나 호출이 실패하면 빈 결과 (동의어 추천만 쓴다). 추천은 확정이 아니라 사람이 매핑 화면에서 확인한다.
 * 선택지(컬럼·필드·허용 값)는 답 스키마의 enum으로도 넘겨 그 밖의 값은 나오지 않게 한다. 받은 뒤에도 한 번 더 확인한다.
 */
@Slf4j
@Component
public class LlmMappingAdvisor {

    private static final int SAMPLES = 5;
    private static final String INSTRUCTIONS = """
            너는 한국 회사의 인사 엑셀 컬럼을 HR 시스템 필드에 연결한다.
            columns의 각 컬럼에 fields 중 가장 맞는 필드 이름(name)을 고르고, 맞는 것이 없으면 null로 둔다.
            한 필드는 한 컬럼에만 연결한다. takenFields에 있는 필드는 이미 연결됐으니 쓰지 않는다.
            samples는 실제 값이 아니라 모양이다 (숫자→9, 한글→가, 영문→a).
            설명 없이 JSON만 답한다: {"mappings":[{"column":"원본 컬럼","field":"필드 이름 또는 null","reason":"한 줄 근거"}]}
            """;

    private static final String VALUE_INSTRUCTIONS = """
            너는 한국 회사 인사 엑셀의 코드성 값을 HR 시스템의 허용 값으로 바꾼다.
            values의 각 값이 allowed 중 어느 것을 뜻하는지 고르고, 뜻이 분명하지 않으면 null로 둔다.
            allowed의 key(한글 표기)를 그대로 답한다.
            설명 없이 JSON만 답한다: {"values":[{"value":"원본 값","target":"허용 값 key 또는 null","reason":"한 줄 근거"}]}
            """;

    /** 필드 이름(값 추천이면 허용 값 한글 표기)과 한 줄 근거 */
    public record Advice(String field, String reason) {
    }

    private final LlmClient llm;
    private final ObjectMapper objectMapper;

    public LlmMappingAdvisor(LlmClient llm, ObjectMapper objectMapper) {
        this.llm = llm;
        this.objectMapper = objectMapper;
    }

    public boolean enabled() {
        return llm.enabled();
    }

    /**
     * @param samples 컬럼 → 원본 값 몇 개 (여기서 모양으로 바꿔 보낸다)
     * @param taken   동의어로 이미 연결된 필드
     * @return 컬럼 → 추천 (스키마에 있고, 아직 안 쓴 필드만)
     */
    public Map<String, Advice> advise(EntitySchema schema, Map<String, List<String>> samples, Set<String> taken) {
        if (!enabled() || samples.isEmpty()) {
            return Map.of();
        }
        try {
            List<String> fields = schema.fields().stream().map(FieldSpec::name).filter(f -> !taken.contains(f)).toList();
            String text = llm.call(INSTRUCTIONS, prompt(schema, samples, taken), "column_mapping",
                    answerSchema("mappings", "column", List.copyOf(samples.keySet()), "field", fields));
            return parse(text, schema, samples.keySet(), taken);
        } catch (RuntimeException e) {
            log.warn("LLM 매핑 추천 실패, 동의어 추천만 사용: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 허용 값 목록에 없는 값마다 어느 허용 값인지 추천 (재직 'Y' → '재직'). 값 자체를 보내므로 코드성 값만 넘길 것
     *
     * @return 원본 값 → 추천 (허용 값의 한글 표기)
     */
    public Map<String, Advice> adviseValues(FieldSpec field, List<String> values) {
        if (!enabled() || values.isEmpty()) {
            return Map.of();
        }
        try {
            String input = objectMapper.writeValueAsString(Map.of("field", field.label(), "allowed", field.enumValues(), "values", values));
            String text = llm.call(VALUE_INSTRUCTIONS, input, "value_mapping",
                    answerSchema("values", "value", values, "target", List.copyOf(field.enumValues().keySet())));
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end < start) {
                return Map.of();
            }
            Map<String, Advice> out = new LinkedHashMap<>();
            for (JsonNode v : objectMapper.readTree(text.substring(start, end + 1)).path("values")) {
                String value = v.path("value").asString(null);
                String target = v.path("target").isNull() ? null : v.path("target").asString(null);
                if (value != null && target != null && values.contains(value) && field.enumValues().containsKey(target)) {
                    out.put(value, new Advice(target, v.path("reason").asString("")));
                }
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("LLM 값 추천 실패: {}", e.getMessage());
            return Map.of();
        }
    }

    /** {list:[{key: keys 중 하나, choice: choices 중 하나 또는 null, reason}]} 모양의 strict 스키마 */
    static Map<String, Object> answerSchema(String list, String key, List<String> keys, String choice, List<String> choices) {
        List<String> nullable = new ArrayList<>(choices);
        nullable.add(null);
        Map<String, Object> item = Map.of("type", "object", "additionalProperties", false, "required", List.of(key, choice, "reason"),
                "properties", Map.of(
                        key, Map.of("type", "string", "enum", keys),
                        choice, Map.of("type", Arrays.asList("string", "null"), "enum", nullable),
                        "reason", Map.of("type", "string")));
        return Map.of("type", "object", "additionalProperties", false, "required", List.of(list),
                "properties", Map.of(list, Map.of("type", "array", "items", item)));
    }

    private String prompt(EntitySchema schema, Map<String, List<String>> samples, Set<String> taken) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (FieldSpec f : schema.fields()) {
            fields.add(Map.of("name", f.name(), "label", f.label(), "type", f.type().name(), "required", f.required()));
        }
        List<Map<String, Object>> columns = new ArrayList<>();
        samples.forEach((column, values) -> columns.add(Map.of("column", column,
                "samples", values.stream().map(LlmMappingAdvisor::shape).distinct().limit(SAMPLES).toList())));
        return objectMapper.writeValueAsString(Map.of("fields", fields, "takenFields", taken, "columns", columns));
    }

    /** 응답에서 JSON을 꺼내 검증: 보낸 컬럼, 스키마에 있는 필드, 아직 안 쓴 필드, 한 필드 한 컬럼 */
    Map<String, Advice> parse(String text, EntitySchema schema, Set<String> columns, Set<String> taken) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) {
            return Map.of();
        }
        JsonNode root = objectMapper.readTree(text.substring(start, end + 1));
        Map<String, Advice> out = new LinkedHashMap<>();
        Set<String> used = new HashSet<>(taken);
        for (JsonNode m : root.path("mappings")) {
            String column = m.path("column").asString(null);
            String field = m.path("field").isNull() ? null : m.path("field").asString(null);
            if (column == null || field == null || !columns.contains(column) || schema.field(field).isEmpty() || !used.add(field)) {
                continue;
            }
            out.put(column, new Advice(field, m.path("reason").asString("")));
        }
        return out;
    }

    /** 값의 모양만 남긴다: 숫자 → 9, 한글 → 가, 영문 → a. 숫자 바로 뒤의 날짜·금액 단위(년·월·일·원·시·분)와 기호는 그대로 */
    static String shape(String value) {
        StringBuilder sb = new StringBuilder();
        char prev = ' ';
        for (char ch : value.strip().toCharArray()) {
            char before = prev;
            prev = ch;
            if (Character.isDigit(ch)) {
                sb.append('9');
            } else if ("년월일원시분".indexOf(ch) >= 0 && Character.isDigit(before)) {
                sb.append(ch);
            } else if (ch >= '가' && ch <= '힣') {
                sb.append('가');
            } else if (Character.isLetter(ch)) {
                sb.append('a');
            } else {
                sb.append(ch);
            }
        }
        return sb.length() > 30 ? sb.substring(0, 30) + "…" : sb.toString();
    }
}
