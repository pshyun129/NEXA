package com.ssafy.migrationservice.domain.migration.dto;

import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationEntity;
import com.ssafy.migrationservice.domain.migration.validation.issue.Severity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record FileDetailResponse(
        Long fileId,
        String fileName,
        String sheetName,
        Integer headerRow,
        MigrationEntity entity,
        int rows,
        LocalDateTime uploadedAt,
        List<ColumnProfile> columns,
        List<ColumnMappingView> columnMappings,
        List<String> missingRequired,
        Map<Severity, Long> issueTotals
) {
}
