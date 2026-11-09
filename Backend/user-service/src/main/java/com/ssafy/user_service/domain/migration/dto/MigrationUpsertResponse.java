package com.ssafy.user_service.domain.migration.dto;

/** 이 요청으로 새로 만든 행 수 / 기준 키가 이미 있어 수정한 행 수 */
public record MigrationUpsertResponse(int created, int updated) {
}
