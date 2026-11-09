package com.ssafy.migrationservice.domain.migration.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.Map;

/** 원본 컬럼명 → 새 값. 빈 문자열이나 null이면 값을 비운다 */
public record RowUpdateRequest(@NotEmpty Map<String, String> values) {
}
