package com.ssafy.approval_service.domain.document.model.dto;

/** 문서 생성 결과. docNo 는 서버가 만든 문서번호 */
public record DocumentCreateResponse(Long documentId, String docNo) {
}
