package com.ssafy.migrationservice.domain.migration.repository;

import com.ssafy.migrationservice.domain.migration.entity.MigrationJob;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MigrationJobRepository extends JpaRepository<MigrationJob, Long> {
}
