package com.ssafy.migrationservice.domain.migration.dto;

import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationEntity;

/** 적재된 묶음별 원본 행 수 vs 대상 서비스에 들어간 수 */
public record ReconciliationItem(String batchId, MigrationEntity entity, String metric, long source, long loaded,
                                 boolean match) {
}
