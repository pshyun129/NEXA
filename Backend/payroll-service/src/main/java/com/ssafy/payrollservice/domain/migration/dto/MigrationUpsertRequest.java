package com.ssafy.payrollservice.domain.migration.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * migration-service 가 보내는 upsert 묶음 (한 번에 최대 500행). user-service 이관 내부 API와 같은 모양.
 * rowNo 는 원본 파일의 행 번호 (오류 메시지에 쓴다)
 */
public record MigrationUpsertRequest(
        @NotNull @Size(max = 1000) List<@Valid @NotNull Row> rows
) {

    public record Row(int rowNo, @NotNull Map<String, Object> data) {
    }
}
