package com.ssafy.approval_service.domain.document.service;

import com.ssafy.approval_service.domain.document.model.dto.DocumentCreateResponse;

/**
 * 문서 생성 결과. created 가 false 면 같은 요청 키로 이미 만든 문서를 돌려준 것 (컨트롤러는 201 대신 200)
 */
public record DocumentCreateResult(DocumentCreateResponse response, boolean created) {
}
