package com.ssafy.migrationservice.domain.migration.dto;

import com.ssafy.migrationservice.domain.migration.validation.issue.Severity;

import java.util.Map;

/** applied: 바뀐 셀 수 / issueTotals: 재검증 후 작업 전체 건수 */
public record FixApplyResponse(int applied, Map<Severity, Long> issueTotals) {
}
