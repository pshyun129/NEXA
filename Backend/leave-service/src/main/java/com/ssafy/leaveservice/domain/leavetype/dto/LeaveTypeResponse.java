package com.ssafy.leaveservice.domain.leavetype.dto;

import com.ssafy.leaveservice.domain.leavetype.entity.*;

public record LeaveTypeResponse(
        Long leaveTypeId,
        String name,
        boolean paid,
        BalanceMode balanceMode,
        RequestUnit requestUnit,
        Integer maxMinutes,
        BalanceTypeResponse balanceType,
        boolean isActive
) {
    public static LeaveTypeResponse from(LeaveType type) {
        LeaveBalanceType balance = type.getBalanceType();
        return new LeaveTypeResponse(type.getLeaveTypeId(), type.getName(), type.isPaid(),
                type.getBalanceMode(), type.getRequestUnit(), type.getMaxMinutes(),
                balance == null ? null : new BalanceTypeResponse(
                        balance.getBalanceTypeId(), balance.getName(), balance.isActive()),
                type.isActive());
    }

    public record BalanceTypeResponse(Long balanceTypeId, String name, boolean active) {
    }
}
