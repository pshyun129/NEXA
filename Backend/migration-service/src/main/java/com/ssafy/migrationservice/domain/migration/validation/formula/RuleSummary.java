package com.ssafy.migrationservice.domain.migration.validation.formula;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 파일 하나의 수식 규칙을 LLM이 사람 말로 정리한 것 (다시 만들기 전까지 재사용) */
@Entity
@Table(name = "rule_summary")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RuleSummary {

    @Id
    @Column(name = "file_id")
    private Long fileId;

    @Column(name = "summary", nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Column(name = "model", length = 100)
    private String model;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public RuleSummary(Long fileId, String summary, String model) {
        this.fileId = fileId;
        this.summary = summary;
        this.model = model;
        this.createdAt = LocalDateTime.now();
    }
}
