package com.ssafy.migrationservice.domain.migration.dto;

import com.ssafy.migrationservice.domain.migration.validation.issue.IssueRepository.IssueGroup;
import com.ssafy.migrationservice.domain.migration.validation.issue.Severity;
import com.ssafy.migrationservice.domain.migration.validation.issue.ValidationIssue;

import java.util.List;
import java.util.Map;

/** totals: 심각도별 건수 / groups: 원인별 묶음 (화면 기본 보기) / content: 행 단위 목록 (페이지) */
public record IssueListResponse(
        Map<Severity, Long> totals,
        List<IssueGroup> groups,
        List<ValidationIssue> content,
        int page,
        int size,
        long totalElements
) {
}
