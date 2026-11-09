package com.ssafy.attendanceservice.domain.attendance.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 결재 결과 이벤트 (approval-service, 토픽 approval.document.result, DOCS/approval-result-event.md) */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApprovalResultEvent(
        String eventId,         // 이벤트마다 고유
        String eventType,       // DOCUMENT_COMPLETED
        Long documentId,        // 결재 문서 ID
        String sourceType,      // LEAVE | ATTENDANCE
        Long sourceId,          // 원본 신청 ID (근태면 attendance_request.att_req_id)
        String requestKey,      // 결재 요청 키
        String status           // APPROVED | REJECTED | CANCEL
) {
}
