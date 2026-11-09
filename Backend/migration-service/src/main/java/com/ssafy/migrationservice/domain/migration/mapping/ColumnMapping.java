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

/** 원본 컬럼 → 이관 필드. confirmed=false면 시스템 추천, true면 사람이 확정. suggestedBy: SYNONYM(동의어)·LLM·USER */
@Entity
@Table(name = "column_mapping")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ColumnMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mapping_id")
    private Long mappingId;

    @Column(name = "file_id", nullable = false)
    private Long fileId;

    @Column(name = "source_column", nullable = false)
    private String sourceColumn;

    @Column(name = "target_field", length = 100)
    private String targetField;

    @Column(name = "confirmed", nullable = false)
    private boolean confirmed;

    @Column(name = "suggested_by", length = 20)
    private String suggestedBy;

    @Column(name = "reason", length = 300)
    private String reason;

    @Builder
    private ColumnMapping(Long fileId, String sourceColumn, String targetField, boolean confirmed, String suggestedBy, String reason) {
        this.fileId = fileId;
        this.sourceColumn = sourceColumn;
        this.targetField = targetField;
        this.confirmed = confirmed;
        this.suggestedBy = suggestedBy;
        this.reason = reason;
    }
}
