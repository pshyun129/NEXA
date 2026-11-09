package com.ssafy.migrationservice.domain.migration.validation.issue;

/**
 * 검증 결과 한 건. rowNo가 null이면 파일 단위 오류.
 * value는 원본 값, suggestion은 FIX일 때 바꿀 값.
 */
public record ValidationIssue(
        Long fileId,
        Integer rowNo,
        String field,
        String sourceColumn,
        Severity severity,
        IssueCode code,
        String message,
        String value,
        String suggestion
) {
}
