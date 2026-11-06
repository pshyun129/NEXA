package com.ssafy.payrollservice.domain.migration.dto;

/** 이 batch 로 적재된 원본 행 수. migration 대사 화면이 원본 행 수와 비교한다 */
public record MigrationBatchSummaryResponse(long count) {
}
