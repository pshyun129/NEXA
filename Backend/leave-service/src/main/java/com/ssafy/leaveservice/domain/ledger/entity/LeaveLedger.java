package com.ssafy.leaveservice.domain.ledger.entity;

import com.ssafy.leaveservice.domain.account.entity.LeaveAccount;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestAllocation;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestStatus;
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
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "leave_ledger")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LeaveLedger {

    public static LeaveLedger grant(
            LeaveAccount account,
            Long grantId,
            int minutes,
            Long actorEmpId,
            String reason,
            String requestKey,
            OffsetDateTime asOf
    ) {
        LeaveLedger ledger = new LeaveLedger();
        ledger.account = account;
        ledger.grantId = grantId;
        ledger.eventType = LedgerEventType.GRANT;
        ledger.quantityDelta = minutes;
        ledger.reservedDelta = 0;
        ledger.actorEmpId = actorEmpId;
        ledger.reason = reason;
        ledger.idempotencyKey = requestKey;
        ledger.createdAt = asOf;
        return ledger;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "entry_id")
    private Long entryId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private LeaveAccount account;

    @Column(name = "grant_id", nullable = false)
    private Long grantId;

    @Column(name = "leave_req_id")
    private Long leaveReqId;

    @Column(name = "allocation_id")
    private Long allocationId;

    @Column(name = "reverses_entry_id")
    private Long reversesEntryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    private LedgerEventType eventType;

    @Column(name = "quantity_delta", nullable = false)
    private int quantityDelta;

    @Column(name = "reserved_delta", nullable = false)
    private int reservedDelta;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "idempotency_key", nullable = false, length = 120)
    private String idempotencyKey;

    @Column(name = "actor_emp_id")
    private Long actorEmpId;

    public static LeaveLedger adjust(
            LeaveAccount account,
            Long grantId,
            int quantityDelta,
            Long actorEmpId,
            String reason,
            String idempotencyKey,
            OffsetDateTime createdAt
    ) {
        LeaveLedger ledger = new LeaveLedger();
        ledger.account = account;
        ledger.grantId = grantId;
        ledger.eventType = LedgerEventType.ADJUST;
        ledger.quantityDelta = quantityDelta;
        ledger.reservedDelta = 0;
        ledger.actorEmpId = actorEmpId;
        ledger.reason = reason;
        ledger.idempotencyKey = idempotencyKey;
        ledger.createdAt = createdAt;
        return ledger;
    }

    public static LeaveLedger reserve(
            LeaveAccount account,
            LeaveRequestAllocation allocation,
            OffsetDateTime asOf
    ) {
        LeaveLedger ledger = new LeaveLedger();
        ledger.account = account;
        ledger.grantId = allocation.getGrantId();
        ledger.leaveReqId = allocation.getRequestId();
        ledger.allocationId = allocation.getAllocationId();
        ledger.eventType = LedgerEventType.RESERVE;
        ledger.quantityDelta = 0;
        ledger.reservedDelta = allocation.getAllocatedMinutes();
        ledger.reason = "휴가 신청 배분 예약";
        ledger.createdAt = asOf;
        ledger.idempotencyKey = "leave:reserve:allocation:" + allocation.getAllocationId();
        return ledger;
    }

    public static LeaveLedger settleApprovalReservation(
            LeaveAccount account,
            LeaveRequestAllocation allocation,
            LeaveRequestStatus finalStatus,
            OffsetDateTime asOf
    ) {
        LeaveLedger ledger = new LeaveLedger();
        ledger.account = account;
        ledger.grantId = allocation.getGrantId();
        ledger.leaveReqId = allocation.getRequestId();
        ledger.allocationId = allocation.getAllocationId();
        boolean approved = finalStatus == LeaveRequestStatus.APPROVED;
        ledger.eventType = approved ? LedgerEventType.CONSUME : LedgerEventType.RELEASE;
        ledger.quantityDelta = approved ? -allocation.getAllocatedMinutes() : 0;
        ledger.reservedDelta = -allocation.getAllocatedMinutes();
        ledger.reason = switch (finalStatus) {
            case APPROVED -> "최종 결재 승인에 따른 예약 사용";
            case REJECTED -> "최종 결재 반려에 따른 예약 해제";
            case WITHDRAWN -> "기안자 결재 문서 회수에 따른 예약 해제";
            default -> throw new IllegalArgumentException("Unsupported settlement status");
        };
        ledger.createdAt = asOf;
        String operation = switch (finalStatus) {
            case APPROVED -> "consume";
            case REJECTED -> "release";
            case WITHDRAWN -> "withdraw";
            default -> throw new IllegalArgumentException("Unsupported settlement status");
        };
        ledger.idempotencyKey = "leave:approval:" + operation + ":request:" + allocation.getRequestId()
                + ":allocation:" + allocation.getAllocationId();
        return ledger;
    }

    public static LeaveLedger releaseForAdminCancellation(
            LeaveAccount account,
            LeaveRequestAllocation allocation,
            OffsetDateTime asOf
    ) {
        LeaveLedger ledger = new LeaveLedger();
        ledger.account = account;
        ledger.grantId = allocation.getGrantId();
        ledger.leaveReqId = allocation.getRequestId();
        ledger.allocationId = allocation.getAllocationId();
        ledger.eventType = LedgerEventType.RELEASE;
        ledger.quantityDelta = 0;
        ledger.reservedDelta = -allocation.getAllocatedMinutes();
        ledger.reason = "관리자 직접 취소에 따른 예약 해제 (신청 " + allocation.getRequestId() + ")";
        ledger.createdAt = asOf;
        ledger.idempotencyKey = "leave:admin-cancel:request:" + allocation.getRequestId() + ":allocation:" + allocation.getAllocationId();
        return ledger;
    }

    public static LeaveLedger restoreForAdminCancellation(
            LeaveLedger consume,
            Long administratorId,
            OffsetDateTime asOf
    ) {
        LeaveLedger restore = new LeaveLedger();
        restore.account = consume.account;
        restore.grantId = consume.grantId;
        restore.leaveReqId = consume.leaveReqId;
        restore.allocationId = consume.allocationId;
        restore.reversesEntryId = consume.entryId;
        restore.eventType = LedgerEventType.RESTORE;
        restore.quantityDelta = Math.negateExact(consume.quantityDelta);
        restore.reservedDelta = 0;
        restore.actorEmpId = administratorId;
        restore.reason = "관리자 승인 휴가 취소에 따른 복원 (신청 " + consume.leaveReqId + ")";
        restore.createdAt = asOf;
        restore.idempotencyKey = "leave:admin-restore:consume:" + consume.entryId;
        return restore;
    }

    public static LeaveLedger release(
            LeaveAccount account,
            LeaveRequestAllocation allocation,
            OffsetDateTime asOf
    ) {
        LeaveLedger ledger = new LeaveLedger();
        ledger.account = account;
        ledger.grantId = allocation.getGrantId();
        ledger.leaveReqId = allocation.getRequestId();
        ledger.allocationId = allocation.getAllocationId();
        ledger.eventType = LedgerEventType.RELEASE;
        ledger.quantityDelta = 0;
        ledger.reservedDelta = -allocation.getAllocatedMinutes();
        ledger.reason = "결재 문서 미생성 확정에 따른 예약 해제";
        ledger.createdAt = asOf;
        ledger.idempotencyKey = "leave:release:allocation:" + allocation.getAllocationId();
        return ledger;
    }
}
