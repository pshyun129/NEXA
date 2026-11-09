package com.ssafy.leaveservice.domain.grant.dto;

import com.ssafy.leaveservice.domain.account.entity.LeaveAccount;
import com.ssafy.leaveservice.domain.grant.entity.GrantKind;
import com.ssafy.leaveservice.domain.grant.entity.LeaveGrant;
import com.ssafy.leaveservice.domain.leavetype.entity.LeaveBalanceType;

import java.time.OffsetDateTime;

public record MyLeaveGrantResponse(
        Long grantId,
        Long accountId,
        Long balanceTypeId,
        String balanceTypeName,
        Long policyVersionId,
        Long ruleId,
        Long parentGrantId,
        GrantKind grantKind,
        OffsetDateTime grantedOn,
        int originalMinutes,
        OffsetDateTime validFrom,
        OffsetDateTime expiresOn,
        OffsetDateTime createdAt
) {

    public static MyLeaveGrantResponse from(LeaveGrant grantRecord) {
        LeaveAccount account = grantRecord.getAccount();
        LeaveBalanceType balanceType = account.getBalanceType();
        return new MyLeaveGrantResponse(
                grantRecord.getGrantId(),
                account.getAccountId(),
                balanceType.getBalanceTypeId(),
                balanceType.getName(),
                grantRecord.getPolicyVersionId(),
                grantRecord.getRuleId(),
                grantRecord.getParentGrantId(),
                grantRecord.getGrantKind(),
                grantRecord.getGrantedOn(),
                grantRecord.getOriginalMinutes(),
                grantRecord.getValidFrom(),
                grantRecord.getExpiresOn(),
                grantRecord.getCreatedAt()
        );
    }
}
