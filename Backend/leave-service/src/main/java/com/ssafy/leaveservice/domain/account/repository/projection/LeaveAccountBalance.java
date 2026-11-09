package com.ssafy.leaveservice.domain.account.repository.projection;

import java.math.BigDecimal;

public interface LeaveAccountBalance {

    Long getAccountId();

    Long getBalanceTypeId();

    String getBalanceTypeName();

    BigDecimal getQuantityMinutes();

    BigDecimal getReservedMinutes();

    boolean getInvalidGrant();
}
