package com.ssafy.migrationservice.domain.migration.repository;

import com.ssafy.migrationservice.domain.migration.entity.MigrationFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MigrationFileRepository extends JpaRepository<MigrationFile, Long> {

    List<MigrationFile> findByMigrationIdOrderByFileIdAsc(Long migrationId);

    Optional<MigrationFile> findByFileIdAndMigrationId(Long fileId, Long migrationId);
}
