package com.ssafy.approval_service.domain.document.model.dto;

/**
 * 목록 조회의 문서함 구분 (box 파라미터). 지정하지 않으면 조회할 수 있는 문서 전체.
 * 문서함은 모두 요청자 본인 기준이다 (관리자도 전체 문서가 아니라 본인 문서함)
 */
public enum DocumentBox {
    DRAFTED,    // 기안함: 요청자가 기안한 문서
    PENDING,    // 결재 대기함: 진행 중이고 지금 요청자 차례인 문서. 오래된 문서부터
    REFERENCE,  // 참조함: 요청자가 참조자(REFERENCE) 또는 열람자(VIEWER)인 문서. 최신 문서부터
    COMPLETED   // 처리함: 요청자가 결재자·합의자로 처리(승인/반려)했거나 전결로 생략(SKIPPED)된 문서. 내가 처리한 시각이 최근인 문서부터
}
