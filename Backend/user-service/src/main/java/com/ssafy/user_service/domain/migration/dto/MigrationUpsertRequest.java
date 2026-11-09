package com.ssafy.user_service.domain.migration.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * migration-service 가 보내는 upsert 묶음 (한 번에 최대 500행).
 * data 의 키는 migration EntitySchemas 의 필드명이고, 매핑된 필드만 온다. 값은 null 일 수 있다.
 */
public record MigrationUpsertRequest(
        @NotNull @Size(max = 1000) List<@Valid @NotNull Row> rows
) {

    /** rowNo: 원본 파일의 행 번호 (오류 메시지에 쓴다) */
    public record Row(int rowNo, @NotNull Map<String, Object> data) {
    }
}
