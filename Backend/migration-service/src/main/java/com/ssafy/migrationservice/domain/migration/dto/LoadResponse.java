package com.ssafy.migrationservice.domain.migration.dto;

import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationStatus;

import java.util.List;

public record LoadResponse(MigrationStatus status, List<BatchView> batches) {
}
