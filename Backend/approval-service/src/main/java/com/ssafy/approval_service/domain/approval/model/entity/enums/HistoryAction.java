package com.ssafy.approval_service.domain.approval.model.entity.enums;

public enum HistoryAction {
    SUBMIT,             // 상신
    APPROVE,            // 승인
    REJECT,             // 반려
    FINAL_APPROVE,      // 최종 승인
    CANCEL,             // 취소
    RESUBMIT,           // 재기안
    APPROVER_ADD,       // 결재자 추가
    APPROVER_REMOVE     // 결재자 제거
}
