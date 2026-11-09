package com.ssafy.attendanceservice.domain.attendance.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 생성된 결재 문서 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApprovalDocument(
        Long documentId,    // 결재 문서 ID (attendance_request.approval_doc_id)
        String docNo        // 문서번호 (예: OVERTIME-2026-0001)
) {
}
