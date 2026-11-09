package com.ssafy.migrationservice.domain.migration.dto;

import com.ssafy.migrationservice.domain.migration.validation.issue.Severity;
import com.ssafy.migrationservice.domain.migration.validation.issue.ValidationIssue;

import java.util.List;
import java.util.Map;

/** rowIssues: 수정 후 그 행에 남은 오류 (비어 있으면 해결) */
public record RowUpdateResponse(int rowNo, List<ValidationIssue> rowIssues, Map<Severity, Long> issueTotals) {
}
