package com.ssafy.migrationservice.domain.migration.mapping;

import com.ssafy.migrationservice.domain.migration.validation.schema.FieldSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 허용 값 목록에 없는 값(재직 'Y', '촉탁직' …)을 어느 허용 값으로 볼지 LLM에게 추천받는다.
 * 파일·필드·값마다 한 번만 묻고 저장한다 (추천이 없었던 것도 저장해 다시 묻지 않음).
 * 코드처럼 몇 가지 값만 쓰는 열만 보낸다: 서로 다른 값 30개 이하, 값마다 20자 이하. 이름 같은 자유 입력은 보내지 않는다.
 */
@Component
@RequiredArgsConstructor
public class EnumValueSuggester {

    private static final int MAX_VALUES = 30;
    private static final int MAX_LENGTH = 20;

    private final LlmMappingAdvisor llmMappingAdvisor;
    private final ValueSuggestionRepository repository;

    /** 원본 값 → 추천 (허용 값의 한글 표기, 근거). 추천이 없는 값은 빠짐 */
    public Map<String, LlmMappingAdvisor.Advice> suggest(Long fileId, FieldSpec field, Collection<String> unknown) {
        Map<String, LlmMappingAdvisor.Advice> out = new HashMap<>();
        Map<String, ValueSuggestion> cached = new HashMap<>();
        repository.findByFileIdAndField(fileId, field.name()).forEach(s -> cached.put(s.getSourceValue(), s));
        List<String> ask = unknown.stream().filter(v -> !cached.containsKey(v)).toList();
        if (!ask.isEmpty() && llmMappingAdvisor.enabled() && unknown.size() <= MAX_VALUES
                && unknown.stream().allMatch(v -> v.length() <= MAX_LENGTH)) {
            Map<String, LlmMappingAdvisor.Advice> advice = llmMappingAdvisor.adviseValues(field, ask);
            for (String v : ask) {
                LlmMappingAdvisor.Advice a = advice.get(v);
                cached.put(v, repository.save(ValueSuggestion.builder().fileId(fileId).field(field.name()).sourceValue(v)
                        .targetValue(a == null ? null : a.field()).reason(a == null ? null : truncate(a.reason())).build()));
            }
        }
        for (String v : unknown) {
            ValueSuggestion s = cached.get(v);
            if (s != null && s.getTargetValue() != null) {
                out.put(v, new LlmMappingAdvisor.Advice(s.getTargetValue(), s.getReason()));
            }
        }
        return out;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 300 ? s : s.substring(0, 300);
    }
}
