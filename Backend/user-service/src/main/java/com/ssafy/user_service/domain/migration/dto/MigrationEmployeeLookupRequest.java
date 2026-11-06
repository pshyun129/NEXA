package com.ssafy.user_service.domain.migration.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 사번으로 사원 찾기 (한 번에 최대 1000개) */
public record MigrationEmployeeLookupRequest(@NotNull @Size(max = 1000) List<@NotNull String> empNos) {
}
