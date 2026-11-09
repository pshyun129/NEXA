package com.ssafy.leaveservice.domain.request.dto;

import com.ssafy.leaveservice.domain.request.entity.AdminCancellationStatus;
import com.ssafy.leaveservice.domain.request.entity.ApprovalCreationStatus;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequest;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminLeaveCancellationResponse(
        Long requestId,
        LeaveRequestStatus status,
        ApprovalCreationStatus approvalCreationStatus,
        Long approvalDocId,
        AdminCancellationStatus adminCancelStatus,
        UUID adminCancelRequestId,
        OffsetDateTime cancelledAt,
        Long cancelledBy,
        String cancelReason,
        String message
) {
    public static AdminLeaveCancellationResponse from(LeaveRequest request) {
        String message = switch (request.getAdminCancelStatus()) {
            case COMPLETED -> "관리자 취소가 완료되었습니다.";
            case FAILED -> "근태 원복이 수행되지 않았음이 확인되어 취소하지 않았습니다.";
            case IN_PROGRESS, UNKNOWN -> "근태 원복 결과 확인이 필요합니다. 승인 상태와 사용량은 유지됩니다.";
        };
        return new AdminLeaveCancellationResponse(
                request.getRequestId(),
                request.getStatus(),
                request.getApprovalCreationStatus(),
                request.getApprovalDocId(),
                request.getAdminCancelStatus(),
                request.getAdminCancelRequestId(),
                request.getCancelledAt(),
                request.getCancelledBy(),
                request.getCancelReason(),
                message
        );
    }
}
