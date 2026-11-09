package com.ssafy.leaveservice.domain.adjustment.dto;

import com.ssafy.leaveservice.domain.ledger.entity.LeaveLedger;
import com.ssafy.leaveservice.domain.ledger.entity.LedgerEventType;

import java.time.OffsetDateTime;

public record LeaveAdjustmentResponse(
        Long entryId,
        Long employeeId,
        Long accountId,
        Long grantId,
        LedgerEventType eventType,
        int quantityDelta,
        int reservedDelta,
        Long actorEmpId,
        String reason,
        OffsetDateTime createdAt
) {
    public static LeaveAdjustmentResponse from(LeaveLedger ledger) {
        return new LeaveAdjustmentResponse(
                ledger.getEntryId(),
                ledger.getAccount().getEmployeeId(),
                ledger.getAccount().getAccountId(),
                ledger.getGrantId(),
                ledger.getEventType(),
                ledger.getQuantityDelta(),
                ledger.getReservedDelta(),
                ledger.getActorEmpId(),
                ledger.getReason(),
                ledger.getCreatedAt()
        );
    }
}
