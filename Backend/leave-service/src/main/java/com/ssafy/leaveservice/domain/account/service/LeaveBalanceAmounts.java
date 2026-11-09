package com.ssafy.leaveservice.domain.account.service;

import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;

import java.math.BigDecimal;

public record LeaveBalanceAmounts(long quantityMinutes, long reservedMinutes, long remainingMinutes) {

    public static LeaveBalanceAmounts from(BigDecimal quantity, BigDecimal reserved) {
        try {
            long quantityMinutes = quantity.longValueExact();
            long reservedMinutes = reserved.longValueExact();
            if (quantityMinutes < 0 || reservedMinutes < 0 || reservedMinutes > quantityMinutes) {
                throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
            }
            return new LeaveBalanceAmounts(quantityMinutes, reservedMinutes, Math.subtractExact(quantityMinutes, reservedMinutes));
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
        }
    }
}
