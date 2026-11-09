package com.ssafy.migrationservice.domain.migration.dto;

import java.util.List;

/**
 * 사원 파일에서 뽑은 기준정보(직위·직급·직책·직무)와 대상 비교. 적재하면 기준정보 → 조직 → 사원 순으로 들어간다.
 * type: POSITION / GRADE / DUTY / JOB_CODE, value: 화면 표기 ('G3 2호봉', 'IT > 개발 > 백엔드'),
 * rankOrder: 새로 만들 직위의 서열 제안 (이미 있는 직위는 대상 값 그대로)
 */
public record ReferencePlanResponse(List<Item> items) {

    /** NEW: 새로 만듦 / EXISTS: 이미 있어 그대로 씀 / AMBIGUOUS: 적재하면 사원이 하나로 못 고름 (적재 막힘) */
    public enum Status { NEW, EXISTS, AMBIGUOUS }

    public record Item(Long fileId, String type, String value, Integer rankOrder, Status status, String message) {
    }
}
