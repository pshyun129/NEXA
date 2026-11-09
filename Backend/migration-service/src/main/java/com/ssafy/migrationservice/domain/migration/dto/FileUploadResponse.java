package com.ssafy.migrationservice.domain.migration.dto;

import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationEntity;
import com.ssafy.migrationservice.domain.migration.validation.issue.Severity;

import java.util.List;
import java.util.Map;

/**
 * 업로드하면 매핑 추천과 검증까지 자동으로 돌고, 그 결과 건수를 함께 돌려준다.
 * sheetName은 읽은 시트, sheets는 파일의 모든 시트 (다른 시트를 쓰려면 sheet를 지정해 다시 올린다)
 */
public record FileUploadResponse(Long fileId, MigrationEntity entity, int rows, List<String> headers,
                                 List<String> missingRequired, Map<Severity, Long> issueTotals,
                                 String sheetName, int headerRow, List<Integer> totalRows, List<SheetInfo> sheets) {
}
