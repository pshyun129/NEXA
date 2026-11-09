package com.ssafy.approval_service.domain.document.model.dto;

/** 문서 건수 (목록과 같은 조건). 결재 대기 카운터처럼 자주 갱신하는 곳에서 목록 전체를 받지 않으려고 쓴다 */
public record DocumentCountResponse(long count) {
}
