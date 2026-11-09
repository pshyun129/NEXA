package com.ssafy.leaveservice.domain.department.service;

import com.ssafy.leaveservice.domain.account.dto.MyLeaveBalanceResponse;
import com.ssafy.leaveservice.domain.account.repository.LeaveAccountRepository;
import com.ssafy.leaveservice.domain.account.repository.projection.DepartmentAccountBalance;
import com.ssafy.leaveservice.domain.account.service.LeaveBalanceAmounts;
import com.ssafy.leaveservice.domain.department.dto.DepartmentLeaveBalancesResponse;
import com.ssafy.leaveservice.domain.department.dto.DepartmentLeaveBalancesResponse.EmployeeBalances;
import com.ssafy.leaveservice.domain.department.dto.DepartmentLeaveScheduleResponse;
import com.ssafy.leaveservice.domain.request.repository.LeaveRequestRepository;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** HR 권한 검증을 마친 내부 직원 ID 집합만 전달받는 DB 조회 구간. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DepartmentLeaveQueryService {
    private final LeaveRequestRepository leaveRequestRepository;
    private final LeaveAccountRepository leaveAccountRepository;

    public List<DepartmentLeaveScheduleResponse> findRequests(List<Long> employeeIds) {
        // 신뢰된 빈 집합은 전체 조회로 확장하지 않음
        if (employeeIds.isEmpty()) {
            return List.of();
        }
        return leaveRequestRepository.findAllWithLeaveTypeByEmployeeIdIn(employeeIds).stream()
                .map(DepartmentLeaveScheduleResponse::from)
                .toList();
    }

    public DepartmentLeaveBalancesResponse findBalances(Long departmentId, List<Long> employeeIds, OffsetDateTime asOf) {
        // 계정이 없는 부서원도 빈 잔액 목록으로 구분
        Map<Long, List<MyLeaveBalanceResponse>> balancesByEmployee = new LinkedHashMap<>();
        for (Long employeeId : employeeIds) {
            balancesByEmployee.put(employeeId, new ArrayList<>());
        }
        if (!employeeIds.isEmpty()) {
            // 한 SELECT로 전 직원 계정을 집계하고 기존 정합성·오버플로 검증 재사용
            List<DepartmentAccountBalance> balances = leaveAccountRepository.findBalancesByEmployeeIds(employeeIds, asOf);
            for (DepartmentAccountBalance balance : balances) {
                List<MyLeaveBalanceResponse> employeeBalances = balancesByEmployee.get(balance.getEmployeeId());
                if (balance.getInvalidGrant() || employeeBalances == null) {
                    throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
                }
                LeaveBalanceAmounts amounts = LeaveBalanceAmounts.from(balance.getQuantityMinutes(), balance.getReservedMinutes());
                employeeBalances.add(new MyLeaveBalanceResponse(
                        balance.getAccountId(), balance.getBalanceTypeId(), balance.getBalanceTypeName(),
                        amounts.quantityMinutes(), amounts.reservedMinutes(), amounts.remainingMinutes()));
            }
        }

        // 기준 시각과 직원별 계정 잔액 반환
        List<EmployeeBalances> employees = new ArrayList<>();
        for (Map.Entry<Long, List<MyLeaveBalanceResponse>> employee : balancesByEmployee.entrySet()) {
            employees.add(new EmployeeBalances(employee.getKey(), List.copyOf(employee.getValue())));
        }
        return new DepartmentLeaveBalancesResponse(departmentId, asOf, List.copyOf(employees));
    }
}
