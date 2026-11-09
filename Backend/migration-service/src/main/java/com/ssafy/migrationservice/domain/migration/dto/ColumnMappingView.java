package com.ssafy.migrationservice.domain.migration.dto;

/**
 * targetField가 null이면 연결 안 된 컬럼. confirmed=false면 시스템 추천.
 * suggestedBy: SYNONYM(동의어 사전)·LLM(모델 추천, reason에 근거)·USER(사람이 바꿈)
 */
public record ColumnMappingView(String sourceColumn, String targetField, String targetLabel, boolean confirmed,
                                String suggestedBy, String reason) {
}
