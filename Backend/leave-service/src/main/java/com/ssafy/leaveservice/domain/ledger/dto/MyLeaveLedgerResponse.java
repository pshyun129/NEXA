package com.ssafy.leaveservice.domain.ledger.dto;

import com.ssafy.leaveservice.domain.account.entity.LeaveAccount;
import com.ssafy.leaveservice.domain.ledger.entity.LeaveLedger;
import com.ssafy.leaveservice.domain.ledger.entity.LedgerEventType;
import com.ssafy.leaveservice.domain.leavetype.entity.LeaveBalanceType;

import java.time.OffsetDateTime;

public record MyLeaveLedgerResponse(
        Long entryId,
        Long accountId,
        Long balanceTypeId,
        String balanceTypeName,
        Long grantId,
        Long leaveReqId,
        Long reversesEntryId,
        LedgerEventType eventType,
        int quantityDelta,
        int reservedDelta,
        String reason,
        OffsetDateTime createdAt,
        String idempotencyKey
) {

    public static MyLeaveLedgerResponse from(LeaveLedger ledgerEntry) {
        LeaveAccount account = ledgerEntry.getAccount();
        LeaveBalanceType balanceType = account.getBalanceType();
        return new MyLeaveLedgerResponse(
                ledgerEntry.getEntryId(),
                account.getAccountId(),
                balanceType.getBalanceTypeId(),
                balanceType.getName(),
                ledgerEntry.getGrantId(),
                ledgerEntry.getLeaveReqId(),
                ledgerEntry.getReversesEntryId(),
                ledgerEntry.getEventType(),
                ledgerEntry.getQuantityDelta(),
                ledgerEntry.getReservedDelta(),
                ledgerEntry.getReason(),
                ledgerEntry.getCreatedAt(),
                ledgerEntry.getIdempotencyKey()
        );
    }
}
