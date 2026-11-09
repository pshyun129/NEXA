package com.ssafy.migrationservice.domain.migration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** companyId는 인증 연동 전까지 요청으로 받는다 (연동 후 토큰에서 꺼냄) */
public record MigrationCreateRequest(
        @NotNull Long companyId,
        @NotBlank String name,
        LocalDate cutoverDate           // 전환 기준일 (yyyy-MM-dd)
) {
}
