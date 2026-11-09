package com.ssafy.migrationservice.domain.migration.entity.enums;

/** 이관 작업 상태. DRAFT(업로드·매핑·검증 중) → LOADED(적재됨, 되돌리기 가능) → COMPLETED(전환 완료, 조회만) */
public enum MigrationStatus {
    DRAFT,
    LOADED,
    COMPLETED
}
