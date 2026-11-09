package com.ssafy.migrationservice.domain.migration.load;

import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 적재 묶음. 파일 하나를 대상 서비스에 넣은 단위이자 되돌리기 단위 */
@Entity
@Table(name = "migration_batch")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MigrationBatch {

    @Id
    @Column(name = "batch_id", length = 64)
    private String batchId;

    @Column(name = "migration_id", nullable = false)
    private Long migrationId;

    @Column(name = "file_id", nullable = false)
    private Long fileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity", nullable = false, length = 40)
    private MigrationEntity entity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BatchStatus status;

    @Column(name = "source_rows", nullable = false)
    private int sourceRows;

    @Column(name = "created_count", nullable = false)
    private int createdCount;

    @Column(name = "updated_count", nullable = false)
    private int updatedCount;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Builder
    private MigrationBatch(String batchId, Long migrationId, Long fileId, MigrationEntity entity, int sourceRows) {
        this.batchId = batchId;
        this.migrationId = migrationId;
        this.fileId = fileId;
        this.entity = entity;
        this.sourceRows = sourceRows;
        this.status = BatchStatus.LOADING;
        this.startedAt = LocalDateTime.now();
    }

    public void loaded(int created, int updated) {
        this.status = BatchStatus.LOADED;
        this.createdCount = created;
        this.updatedCount = updated;
        this.finishedAt = LocalDateTime.now();
    }

    public void failed(String error) {
        this.status = BatchStatus.FAILED;
        this.error = error;
        this.finishedAt = LocalDateTime.now();
    }

    public void rolledBack() {
        this.status = BatchStatus.ROLLED_BACK;
        this.finishedAt = LocalDateTime.now();
    }
}
