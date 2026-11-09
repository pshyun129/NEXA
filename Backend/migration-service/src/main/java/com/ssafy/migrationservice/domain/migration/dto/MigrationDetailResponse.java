package com.ssafy.migrationservice.domain.migration.dto;

import com.ssafy.migrationservice.domain.migration.entity.MigrationFile;
import com.ssafy.migrationservice.domain.migration.entity.MigrationJob;
import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationEntity;
import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record MigrationDetailResponse(
        Long migrationId,
        Long companyId,
        String name,
        LocalDate cutoverDate,
        MigrationStatus status,
        LocalDateTime createdAt,
        List<FileSummary> files,
        List<BatchView> batches
) {

    public record FileSummary(Long fileId, String fileName, MigrationEntity entity, int rows, LocalDateTime uploadedAt) {

        static FileSummary from(MigrationFile f) {
            return new FileSummary(f.getFileId(), f.getFileName(), f.getEntity(), f.getRowCount(), f.getUploadedAt());
        }
    }

    public static MigrationDetailResponse of(MigrationJob job, List<MigrationFile> files, List<BatchView> batches) {
        return new MigrationDetailResponse(job.getMigrationId(), job.getCompanyId(), job.getName(),
                job.getCutoverDate(), job.getStatus(), job.getCreatedAt(),
                files.stream().map(FileSummary::from).toList(), batches);
    }
}
