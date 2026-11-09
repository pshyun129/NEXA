package com.ssafy.migrationservice.domain.migration.dto;

import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationEntity;
import com.ssafy.migrationservice.domain.migration.load.BatchStatus;
import com.ssafy.migrationservice.domain.migration.load.MigrationBatch;

import java.time.LocalDateTime;

public record BatchView(String batchId, Long fileId, MigrationEntity entity, BatchStatus status, int sourceRows,
                        int created, int updated, String error, LocalDateTime startedAt, LocalDateTime finishedAt) {

    public static BatchView from(MigrationBatch b) {
        return new BatchView(b.getBatchId(), b.getFileId(), b.getEntity(), b.getStatus(), b.getSourceRows(),
                b.getCreatedCount(), b.getUpdatedCount(), b.getError(), b.getStartedAt(), b.getFinishedAt());
    }
}
