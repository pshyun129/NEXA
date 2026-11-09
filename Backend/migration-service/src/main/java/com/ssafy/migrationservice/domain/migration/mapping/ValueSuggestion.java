package com.ssafy.migrationservice.domain.migration.mapping;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 허용 값 목록에 없는 원본 값 → LLM이 추천한 허용 값 (targetValue가 null이면 추천 없음). 재검증 때 다시 묻지 않으려고 저장 */
@Entity
@Table(name = "value_suggestion")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ValueSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "suggestion_id")
    private Long suggestionId;

    @Column(name = "file_id", nullable = false)
    private Long fileId;

    @Column(name = "field", nullable = false, length = 100)
    private String field;

    @Column(name = "source_value", nullable = false)
    private String sourceValue;

    @Column(name = "target_value", length = 100)
    private String targetValue;

    @Column(name = "reason", length = 300)
    private String reason;

    @Builder
    private ValueSuggestion(Long fileId, String field, String sourceValue, String targetValue, String reason) {
        this.fileId = fileId;
        this.field = field;
        this.sourceValue = sourceValue;
        this.targetValue = targetValue;
        this.reason = reason;
    }
}
