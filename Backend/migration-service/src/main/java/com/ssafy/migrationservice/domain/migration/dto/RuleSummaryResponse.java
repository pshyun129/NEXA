package com.ssafy.migrationservice.domain.migration.dto;

import com.ssafy.migrationservice.domain.migration.entity.MigrationFile;
import com.ssafy.migrationservice.domain.migration.validation.formula.RuleSummary;

import java.time.LocalDateTime;

/** 수식 규칙 요약 (마크다운). cached=true면 이전에 만든 것 */
public record RuleSummaryResponse(Long fileId, String sheetName, String summary, String model, LocalDateTime createdAt,
                                  boolean cached) {

    public static RuleSummaryResponse of(MigrationFile file, RuleSummary s, boolean cached) {
        return new RuleSummaryResponse(file.getFileId(), file.getSheetName(), s.getSummary(), s.getModel(), s.getCreatedAt(), cached);
    }
}
