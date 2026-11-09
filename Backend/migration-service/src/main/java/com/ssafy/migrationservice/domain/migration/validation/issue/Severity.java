package com.ssafy.migrationservice.domain.migration.validation.issue;

/** BLOCK: 해결 전 적재 불가 / WARN: 확인 후 진행 가능 / FIX: 자동 정리 제안 (적용하면 해결) */
public enum Severity {
    BLOCK,
    WARN,
    FIX
}
