package com.ssafy.approval_service.domain.document.model.entity.enums;

/** 문서가 만들어진 곳. DIRECT 가 아니면 source_id 에 원본 신청의 ID 를 함께 저장한다 */
public enum SourceType {
    DIRECT,         // 사용자가 결재 화면에서 직접 기안
    LEAVE,          // leave-service 휴가 신청
    ATTENDANCE,     // attendance-service 근태 신청
    AUTOMATION      // ai-service 업무 자동화 워크플로우 (source_id = 워크플로우 실행 ID)
}
