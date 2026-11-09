package com.ssafy.leaveservice.domain.request.entity;

import com.ssafy.leaveservice.domain.leavetype.entity.LeaveType;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "leave_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LeaveRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "leave_req_id")
    private Long requestId;

    @Column(name = "emp_id", nullable = false)
    private Long employeeId;

    @Column(name = "policy_version_id", nullable = false)
    private Long policyVersionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "leave_type_id", nullable = false)
    private LeaveType leaveType;

    @Column(name = "approval_doc_id")
    private Long approvalDocId;

    @Column(name = "total_minutes", nullable = false)
    private int totalMinutes;

    @Column(name = "total_deduct_minutes", nullable = false)
    private int totalDeductMinutes;

    @Column(name = "reason", length = 2000)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private LeaveRequestStatus status;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    @Column(name = "cancel_reason", length = 2000)
    private String cancelReason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "submission_key", length = 128)
    private String submissionKey;

    @Column(name = "submission_payload_hash", length = 64)
    private String submissionPayloadHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_creation_status", length = 20)
    private ApprovalCreationStatus approvalCreationStatus;

    @Column(name = "approval_request_id")
    private java.util.UUID approvalRequestId;

    @Column(name = "approval_status_changed_at")
    private OffsetDateTime approvalStatusChangedAt;

    @Column(name = "approval_last_error", length = 500)
    private String approvalLastError;

    @Column(name = "approval_attempt_count")
    private Integer approvalAttemptCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "admin_cancel_status", length = 20)
    private AdminCancellationStatus adminCancelStatus;

    @Column(name = "admin_cancel_request_id")
    private java.util.UUID adminCancelRequestId;

    @Column(name = "admin_cancel_requested_by")
    private Long adminCancelRequestedBy;

    @Column(name = "admin_cancel_reason", length = 2000)
    private String adminCancelReason;

    @Column(name = "admin_cancel_status_changed_at")
    private OffsetDateTime adminCancelStatusChangedAt;

    @Column(name = "admin_cancel_target", length = 20)
    private String adminCancelTarget;

    @Column(name = "admin_cancel_requested_at")
    private OffsetDateTime adminCancelRequestedAt;

    @OneToMany(mappedBy = "leaveRequest", fetch = FetchType.LAZY)
    @OrderBy("leaveDate ASC, startAt ASC, requestDateId ASC")
    private List<LeaveRequestDate> dates = new ArrayList<>();

    public void applyFinalApprovalDecision(LeaveRequestStatus finalStatus, OffsetDateTime now) {
        if (status != LeaveRequestStatus.SUBMITTED || approvalCreationStatus != ApprovalCreationStatus.LINKED
                || (finalStatus != LeaveRequestStatus.APPROVED && finalStatus != LeaveRequestStatus.REJECTED
                    && finalStatus != LeaveRequestStatus.WITHDRAWN)
                || cancelledAt != null || cancelledBy != null || cancelReason != null) {
            throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
        }
        status = finalStatus;
        if (finalStatus == LeaveRequestStatus.WITHDRAWN) {
            cancelledAt = now;
            // Approval 회수 API는 기안자 본인만 허용한다.
            cancelledBy = employeeId;
            cancelReason = "기안자 결재 문서 회수";
        }
        updatedAt = now;
    }

    public void startAdminCancellation(
            java.util.UUID operationId,
            Long administratorId,
            String reason,
            OffsetDateTime now
    ) {
        requireApprovedForAdminCancellation();
        if (adminCancelStatus != null) {
            throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
        }
        adminCancelStatus = AdminCancellationStatus.IN_PROGRESS;
        adminCancelTarget = "ATTENDANCE";
        adminCancelRequestedAt = now;
        adminCancelRequestId = operationId;
        adminCancelRequestedBy = administratorId;
        adminCancelReason = reason;
        adminCancelStatusChangedAt = now;
        updatedAt = now;
    }

    public void recordAdminCancellationUnknown(OffsetDateTime now) {
        requirePendingAdminCancellation();
        adminCancelStatus = AdminCancellationStatus.UNKNOWN;
        adminCancelStatusChangedAt = now;
        updatedAt = now;
    }

    public void failAdminCancellation(OffsetDateTime now) {
        requirePendingAdminCancellation();
        adminCancelStatus = AdminCancellationStatus.FAILED;
        adminCancelStatusChangedAt = now;
        updatedAt = now;
    }

    public void completeAdminCancellation(OffsetDateTime now) {
        requireApprovedForAdminCancellation();
        requirePendingAdminCancellation();
        status = LeaveRequestStatus.CANCELLED;
        cancelledAt = now;
        cancelledBy = adminCancelRequestedBy;
        cancelReason = adminCancelReason;
        adminCancelStatus = AdminCancellationStatus.COMPLETED;
        adminCancelStatusChangedAt = now;
        updatedAt = now;
    }

    public void requireApprovedForAdminCancellation() {
        // 과거 휴가와 마감 기간의 별도 취소 제한은 업무 정책 확정 후 추가한다.
        if (status != LeaveRequestStatus.APPROVED || approvalCreationStatus != ApprovalCreationStatus.LINKED
                || approvalDocId == null || approvalDocId <= 0) {
            throw new BusinessException(ErrorCode.LEAVE_CANCELLATION_NOT_ALLOWED);
        }
        if (cancelledAt != null || cancelledBy != null || cancelReason != null) {
            throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
        }
    }

    private void requirePendingAdminCancellation() {
        if (adminCancelStatus != AdminCancellationStatus.IN_PROGRESS && adminCancelStatus != AdminCancellationStatus.UNKNOWN) {
            throw new BusinessException(ErrorCode.LEAVE_CANCELLATION_NOT_ALLOWED);
        }
    }

    public static LeaveRequest submitting(
            Long employeeId,
            LeaveType leaveType,
            Long policyVersionId,
            int totalMinutes,
            int totalDeductMinutes,
            String reason,
            String submissionKey,
            String payloadHash,
            OffsetDateTime asOf
    ) {
        LeaveRequest request = new LeaveRequest();
        request.employeeId = employeeId;
        request.leaveType = leaveType;
        request.policyVersionId = policyVersionId;
        request.totalMinutes = totalMinutes;
        request.totalDeductMinutes = totalDeductMinutes;
        request.reason = reason;
        request.status = LeaveRequestStatus.SUBMITTING;
        request.submissionKey = submissionKey;
        request.submissionPayloadHash = payloadHash;
        request.approvalCreationStatus = ApprovalCreationStatus.NOT_SENT;
        request.approvalRequestId = java.util.UUID.randomUUID();
        request.approvalStatusChangedAt = asOf;
        request.approvalAttemptCount = 0;
        request.createdAt = asOf;
        request.updatedAt = asOf;
        return request;
    }

    public void startApprovalCall(OffsetDateTime now) {
        requirePendingApproval();
        if (approvalCreationStatus != ApprovalCreationStatus.NOT_SENT) {
            throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
        }
        int nextAttempt;
        try {
            nextAttempt = Math.addExact(approvalAttemptCount, 1);
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
        }
        approvalAttemptCount = nextAttempt;
        approvalCreationStatus = ApprovalCreationStatus.IN_PROGRESS;
        approvalLastError = null;
        approvalStatusChangedAt = now;
        updatedAt = now;
    }

    public void linkCreatedApproval(Long documentId, OffsetDateTime now) {
        requirePendingApproval();
        approvalDocId = documentId;
        approvalCreationStatus = ApprovalCreationStatus.LINKED;
        status = LeaveRequestStatus.SUBMITTED;
        submittedAt = now;
        approvalLastError = null;
        approvalStatusChangedAt = now;
        updatedAt = now;
    }

    public void recordUnknownApproval(String reason, OffsetDateTime now) {
        requirePendingApproval();
        approvalCreationStatus = ApprovalCreationStatus.UNKNOWN;
        approvalLastError = reason;
        approvalStatusChangedAt = now;
        updatedAt = now;
    }

    public void cancelAfterConfirmedApprovalFailure(String reason, Long actorId, OffsetDateTime now) {
        requirePendingApproval();
        status = LeaveRequestStatus.CANCELLED;
        approvalCreationStatus = ApprovalCreationStatus.FAILED;
        approvalLastError = reason;
        approvalStatusChangedAt = now;
        cancelledAt = now;
        cancelledBy = actorId;
        cancelReason = reason;
        updatedAt = now;
    }

    private void requirePendingApproval() {
        boolean pending = approvalCreationStatus == ApprovalCreationStatus.NOT_SENT
                || approvalCreationStatus == ApprovalCreationStatus.IN_PROGRESS
                || approvalCreationStatus == ApprovalCreationStatus.UNKNOWN;
        if (status != LeaveRequestStatus.SUBMITTING || approvalDocId != null || !pending) {
            throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
        }
    }
}
