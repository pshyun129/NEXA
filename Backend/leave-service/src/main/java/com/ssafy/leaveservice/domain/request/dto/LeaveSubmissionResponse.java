package com.ssafy.leaveservice.domain.request.dto;

import com.ssafy.leaveservice.domain.request.entity.ApprovalCreationStatus;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestStatus;

public record LeaveSubmissionResponse(
        Long requestId,
        LeaveRequestStatus status,
        ApprovalCreationStatus approvalCreationStatus,
        Long approvalDocId,
        String message
) {
    public static LeaveSubmissionResponse from(LeaveSubmissionResult result) {
        String message = switch (result.status()) {
            case SUBMITTED -> "휴가 신청이 제출되었습니다.";
            case SUBMITTING -> "신청 처리 결과 확인이 필요합니다. 기존 신청 키를 유지해주세요.";
            case CANCELLED -> result.approvalCreationStatus() == ApprovalCreationStatus.FAILED
                    ? "결재 문서 미생성이 확인되어 신청이 취소되었습니다." : "이미 취소된 신청입니다.";
            case APPROVED -> "이미 승인된 신청입니다.";
            case REJECTED -> "이미 반려된 신청입니다.";
            case WITHDRAWN -> "이미 철회된 신청입니다.";
            case DRAFT -> "작성 중인 신청입니다.";
        };
        return new LeaveSubmissionResponse(result.requestId(), result.status(), result.approvalCreationStatus(),
                result.approvalDocId(), message);
    }
}
