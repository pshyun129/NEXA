package com.ssafy.migrationservice.domain.migration.mapping;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ColumnMappingRepository extends JpaRepository<ColumnMapping, Long> {

    List<ColumnMapping> findByFileIdOrderByMappingIdAsc(Long fileId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ColumnMapping m where m.fileId = :fileId")
    void deleteByFileId(Long fileId);
}
