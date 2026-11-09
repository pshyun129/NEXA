package com.ssafy.leaveservice.domain.department.dto;

import com.ssafy.leaveservice.domain.account.dto.MyLeaveBalanceResponse;
import java.time.OffsetDateTime;
import java.util.List;

/** 세 잔액 모두 asOf에 유효한 발생분의 분 단위 잔액이며 전체 지급량이 아니다. */
public record DepartmentLeaveBalancesResponse(
        Long departmentId,
        OffsetDateTime asOf,
        List<EmployeeBalances> employees
) {
    public record EmployeeBalances(Long employeeId, List<MyLeaveBalanceResponse> balances) {}
}
