package com.ssafy.payrollservice.domain.migration.dto;

/** 이 요청으로 새로 만든 행 수 / 이미 있어 그대로 둔 행 수 (이관은 있는 값을 바꾸지 않는다) */
public record MigrationUpsertResponse(int created, int updated) {
}
