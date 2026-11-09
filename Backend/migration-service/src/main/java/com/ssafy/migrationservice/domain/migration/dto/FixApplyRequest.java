package com.ssafy.migrationservice.domain.migration.dto;

import com.ssafy.migrationservice.domain.migration.validation.issue.IssueCode;

/** 자동 정리 범위. 모두 비우면 작업 전체의 FIX를 적용, 묶음 하나만 적용하려면 fileId·code·field를 채운다 */
public record FixApplyRequest(Long fileId, IssueCode code, String field) {

    public static FixApplyRequest all() {
        return new FixApplyRequest(null, null, null);
    }
}
