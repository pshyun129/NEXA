package com.ssafy.migrationservice.domain.migration.load;

import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MigrationBatchRepository extends JpaRepository<MigrationBatch, String> {

    List<MigrationBatch> findByMigrationIdOrderByStartedAtAsc(Long migrationId);

    Optional<MigrationBatch> findByBatchIdAndMigrationId(String batchId, Long migrationId);

    /** 그 파일에서 나온 묶음이 하나라도 적재돼 있는지 (사원 파일이면 기준정보 묶음 포함) */
    boolean existsByFileIdAndStatus(Long fileId, BatchStatus status);

    /** 사원 파일 하나에 기준정보·사원 두 묶음이 생기므로 entity 까지 본다 */
    boolean existsByFileIdAndEntityAndStatus(Long fileId, MigrationEntity entity, BatchStatus status);

    boolean existsByMigrationIdAndStatus(Long migrationId, BatchStatus status);

    long countByMigrationIdAndStatus(Long migrationId, BatchStatus status);
}
