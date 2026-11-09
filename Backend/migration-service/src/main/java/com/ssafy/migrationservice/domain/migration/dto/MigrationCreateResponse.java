package com.ssafy.migrationservice.domain.migration.dto;

import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationStatus;

public record MigrationCreateResponse(Long migrationId, MigrationStatus status) {
}
