package com.ssafy.migrationservice.domain.migration.dto;

import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationEntity;

import java.util.List;
import java.util.Map;

/** 미리보기: 실제로 보내지 않고, 보낼 순서·건수·정리된 값 예시만 보여준다 */
/**
 * loadable: 차단 오류 0건 + 자동 정리(FIX) 0건 + 하나로 못 고르는 기준정보(ambiguousReferences) 0건.
 * ambiguousReferences 는 대상 서비스가 꺼져 있으면 0 (적재 때 다시 확인한다)
 */
public record LoadPreviewResponse(boolean loadable, long blockIssues, long fixIssues, long ambiguousReferences, List<Item> files) {

    public record Item(Long fileId, MigrationEntity entity, int rows, List<Map<String, Object>> sample) {
    }
}
