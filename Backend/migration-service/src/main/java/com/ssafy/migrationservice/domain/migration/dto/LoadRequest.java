package com.ssafy.migrationservice.domain.migration.dto;

import java.util.List;

/** fileIds를 비우면 작업의 적재 가능한 파일 전체 (적재 순서는 entity 순서대로) */
public record LoadRequest(List<Long> fileIds) {
}
