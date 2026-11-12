package com.ssafy.migrationservice.domain.migration.dto;

import com.ssafy.migrationservice.domain.migration.entity.MigrationJob;
import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 이관 작업 목록 (최근 만든 것부터). 화면의 작업 고르기용 요약만 */
public record MigrationListResponse(List<Item> content, int page, int size, long totalElements) {

    /** fileCount: 올린 파일 수, loadedBatches: 적재된 묶음 수 */
    public record Item(Long migrationId, String name, MigrationStatus status, LocalDate cutoverDate, LocalDateTime createdAt,
                       long fileCount, long loadedBatches) {

        public static Item of(MigrationJob job, long fileCount, long loadedBatches) {
            return new Item(job.getMigrationId(), job.getName(), job.getStatus(), job.getCutoverDate(), job.getCreatedAt(),
                    fileCount, loadedBatches);
        }
    }
}
