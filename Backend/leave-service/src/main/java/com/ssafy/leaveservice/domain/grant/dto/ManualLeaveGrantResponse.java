package com.ssafy.leaveservice.domain.grant.dto;

import java.time.OffsetDateTime;

/** remainingMinutes는 지급 시점에 현재 유효한 발생분을 합산한 계정 가용 잔액이다. */
public record ManualLeaveGrantResponse(
        Long accountId,
        Long grantId,
        Long ledgerEntryId,
        Long employeeId,
        Long balanceTypeId,
        Long policyVersionId,
        ManualLeaveGrantRequest.GrantMode grantMode,
        int grantMinutes,
        long remainingMinutes,
        OffsetDateTime asOf
) {}
