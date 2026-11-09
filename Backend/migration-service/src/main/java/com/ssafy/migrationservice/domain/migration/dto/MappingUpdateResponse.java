package com.ssafy.migrationservice.domain.migration.dto;

import com.ssafy.migrationservice.domain.migration.validation.issue.Severity;

import java.util.List;
import java.util.Map;

public record MappingUpdateResponse(List<String> missingRequired, Map<Severity, Long> issueTotals) {
}
