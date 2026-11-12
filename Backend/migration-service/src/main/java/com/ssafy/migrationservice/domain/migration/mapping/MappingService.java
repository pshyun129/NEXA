package com.ssafy.migrationservice.domain.migration.mapping;

import com.ssafy.migrationservice.domain.migration.dto.ColumnMappingView;
import com.ssafy.migrationservice.domain.migration.dto.MappingUpdateRequest;
import com.ssafy.migrationservice.domain.migration.entity.MigrationFile;
import com.ssafy.migrationservice.domain.migration.repository.StagingRowRepository;
import com.ssafy.migrationservice.domain.migration.validation.schema.EntitySchema;
import com.ssafy.migrationservice.domain.migration.validation.schema.EntitySchemas;
import com.ssafy.migrationservice.domain.migration.validation.schema.FieldSpec;
import com.ssafy.migrationservice.global.exception.BusinessException;
import com.ssafy.migrationservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class MappingService {

    private static final int SAMPLE_ROWS = 50;

    private final ColumnMappingRepository mappingRepository;
    private final StagingRowRepository stagingRowRepository;
    private final LlmMappingAdvisor llmMappingAdvisor;

    /** 업로드 직후: 헤더마다 추천 매핑을 만든다 (confirmed=false). 동의어로 못 찾은 컬럼은 LLM 추천을 받는다 */
    @Transactional
    public void suggest(MigrationFile file) {
        mappingRepository.deleteByFileId(file.getFileId());
        Optional<EntitySchema> schema = EntitySchemas.of(file.getEntity());
        Map<String, String> suggested = new HashMap<>();
        schema.ifPresent(s -> suggested.putAll(ColumnMappingSuggester.suggest(List.of(file.getHeaders()), s)));
        Map<String, LlmMappingAdvisor.Advice> llm = schema.map(s -> llmAdvice(file, s, suggested)).orElse(Map.of());
        for (String header : file.getHeaders()) {
            String synonym = suggested.get(header);
            LlmMappingAdvisor.Advice advice = llm.get(header);
            mappingRepository.save(ColumnMapping.builder()
                    .fileId(file.getFileId())
                    .sourceColumn(header)
                    .targetField(synonym != null ? synonym : advice == null ? null : advice.field())
                    .confirmed(false)
                    .suggestedBy(synonym != null ? "SYNONYM" : advice == null ? null : "LLM")
                    .reason(synonym != null || advice == null ? null : truncate(advice.reason()))
                    .build());
        }
    }

    private Map<String, LlmMappingAdvisor.Advice> llmAdvice(MigrationFile file, EntitySchema schema, Map<String, String> suggested) {
        List<String> unmapped = List.of(file.getHeaders()).stream().filter(h -> suggested.get(h) == null).toList();
        if (unmapped.isEmpty() || !llmMappingAdvisor.enabled()) {
            return Map.of();
        }
        Map<String, List<String>> samples = new LinkedHashMap<>();
        unmapped.forEach(h -> samples.put(h, new ArrayList<>()));
        stagingRowRepository.findByFileId(file.getFileId()).stream().limit(SAMPLE_ROWS).forEach(r -> unmapped.forEach(h -> {
            String v = r.values().get(h);
            if (v != null) {
                samples.get(h).add(v);
            }
        }));
        Set<String> taken = new HashSet<>(suggested.values());
        taken.remove(null);
        return llmMappingAdvisor.advise(schema, samples, taken);
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 300 ? s : s.substring(0, 300);
    }

    /**
     * 사람이 매핑을 확정한다. 요청에 없는 컬럼은 기존 값을 유지한다.
     * 없는 필드, 한 필드에 두 컬럼 연결은 거절한다.
     */
    @Transactional
    public void update(MigrationFile file, MappingUpdateRequest request) {
        EntitySchema schema = EntitySchemas.of(file.getEntity())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT, file.getEntity() + "은(는) 아직 매핑을 지원하지 않습니다"));
        Set<String> headers = Set.of(file.getHeaders());

        Map<String, String> next = new HashMap<>();
        Map<String, ColumnMapping> before = new HashMap<>();
        for (ColumnMapping cm : mappingRepository.findByFileIdOrderByMappingIdAsc(file.getFileId())) {
            next.put(cm.getSourceColumn(), cm.getTargetField());
            before.put(cm.getSourceColumn(), cm);
        }
        for (MappingUpdateRequest.Item item : request.columnMappings()) {
            if (!headers.contains(item.sourceColumn())) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "파일에 없는 컬럼입니다: " + item.sourceColumn());
            }
            if (item.targetField() != null && schema.field(item.targetField()).isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "없는 필드입니다: " + item.targetField());
            }
            next.put(item.sourceColumn(), item.targetField());
        }
        Set<String> seen = new HashSet<>();
        for (String target : next.values()) {
            if (target != null && !seen.add(target)) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "한 필드에 여러 컬럼을 연결할 수 없습니다: "
                        + schema.field(target).map(FieldSpec::label).orElse(target));
            }
        }

        mappingRepository.deleteByFileId(file.getFileId());
        for (String header : file.getHeaders()) {
            ColumnMapping old = before.get(header);
            boolean same = old != null && Objects.equals(old.getTargetField(), next.get(header));
            mappingRepository.save(ColumnMapping.builder()
                    .fileId(file.getFileId())
                    .sourceColumn(header)
                    .targetField(next.get(header))
                    .confirmed(true)
                    .suggestedBy(same ? old.getSuggestedBy() : "USER")
                    .reason(same ? old.getReason() : null)
                    .build());
        }
    }

    @Transactional(readOnly = true)
    public List<ColumnMappingView> view(MigrationFile file) {
        Map<String, String> labels = new HashMap<>();
        EntitySchemas.of(file.getEntity()).ifPresent(s -> s.fields().forEach(f -> labels.put(f.name(), f.label())));
        return mappingRepository.findByFileIdOrderByMappingIdAsc(file.getFileId()).stream()
                .map(cm -> new ColumnMappingView(cm.getSourceColumn(), cm.getTargetField(),
                        labels.get(cm.getTargetField()), cm.isConfirmed(), cm.getSuggestedBy(), cm.getReason()))
                .toList();
    }

    /** 필드 → 원본 컬럼 (연결된 것만) */
    @Transactional(readOnly = true)
    public Map<String, String> fieldToColumn(Long fileId) {
        Map<String, String> m = new LinkedHashMap<>();
        for (ColumnMapping cm : mappingRepository.findByFileIdOrderByMappingIdAsc(fileId)) {
            if (cm.getTargetField() != null) {
                m.put(cm.getTargetField(), cm.getSourceColumn());
            }
        }
        return m;
    }

    /** 필수 필드 중 아직 어느 컬럼에도 연결되지 않은 것 (라벨) */
    @Transactional(readOnly = true)
    public List<String> missingRequired(MigrationFile file) {
        Set<String> mapped = new HashSet<>();
        mappingRepository.findByFileIdOrderByMappingIdAsc(file.getFileId())
                .forEach(cm -> {
                    if (cm.getTargetField() != null) {
                        mapped.add(cm.getTargetField());
                    }
                });
        return EntitySchemas.of(file.getEntity()).map(s -> s.fields().stream()
                        .filter(FieldSpec::required)
                        .filter(f -> !mapped.contains(f.name()))
                        .map(FieldSpec::label)
                        .toList())
                .orElse(List.of());
    }
}
