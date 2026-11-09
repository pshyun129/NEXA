package com.ssafy.migrationservice.domain.migration.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** targetField를 null로 보내면 그 컬럼은 이관하지 않는다 */
public record MappingUpdateRequest(@NotNull @Valid List<Item> columnMappings) {

    public record Item(@NotBlank String sourceColumn, String targetField) {
    }
}
