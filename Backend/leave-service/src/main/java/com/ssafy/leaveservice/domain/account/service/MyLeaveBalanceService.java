package com.ssafy.leaveservice.domain.account.service;

import com.ssafy.leaveservice.domain.account.dto.MyLeaveBalanceResponse;
import com.ssafy.leaveservice.domain.account.dto.MyLeaveSummaryResponse;
import com.ssafy.leaveservice.domain.account.repository.LeaveAccountRepository;
import com.ssafy.leaveservice.domain.account.repository.projection.LeaveAccountBalance;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MyLeaveBalanceService {

    private final LeaveAccountRepository leaveAccountRepository;
    private final EmployeeAccess employeeAccess;

    @Transactional(readOnly = true)
    public List<MyLeaveBalanceResponse> getMyBalances() {
        // 현재 로그인 직원 확인
        Long employeeId = employeeAccess.currentEmployeeId();
        if (employeeId == null || employeeId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        // 기준 시각을 한 번 확정하고 현재 잔액 조회
        OffsetDateTime asOf = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        return findBalances(employeeId, asOf);
    }

    @Transactional(readOnly = true)
    @com.ssafy.leaveservice.global.auth.LeaveAdministratorOnly
    public List<MyLeaveBalanceResponse> getEmployeeBalances(Long employeeId) {
        // 관리자 권한 확인
        Long adminEmployeeId = employeeAccess.currentEmployeeId();
        if (!employeeAccess.isAdmin(adminEmployeeId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        // 조회 대상 직원 ID 검증
        if (employeeId == null || employeeId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        // 기준 시각을 한 번 확정하고 본인 조회와 동일한 현재 잔액 반환
        OffsetDateTime asOf = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        return findBalances(employeeId, asOf);
    }

    @Transactional(readOnly = true)
    public MyLeaveSummaryResponse getMySummary() {
        // 현재 로그인 직원 확인
        Long employeeId = employeeAccess.currentEmployeeId();
        if (employeeId == null || employeeId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        // 응답과 SQL에서 공유할 기준 시각을 한 번 확정
        OffsetDateTime asOf = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        List<MyLeaveBalanceResponse> balances = findBalances(employeeId, asOf);

        // 현재 잔액 중심의 1차 요약 응답 생성
        MyLeaveSummaryResponse response = new MyLeaveSummaryResponse(asOf, balances);
        return response;
    }

    @Transactional(readOnly = true)
    public List<MyLeaveBalanceResponse> getMyBalancesAt(OffsetDateTime asOf) {
        // 본인 인증을 유지하며 Preview의 동일한 기준 시각으로 집계
        Long employeeId = employeeAccess.currentEmployeeId();
        if (employeeId == null || employeeId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return findBalances(employeeId, asOf);
    }

    private List<MyLeaveBalanceResponse> findBalances(Long employeeId, OffsetDateTime asOf) {
        // 대상 직원 전체 계정의 원장 집계 조회
        List<LeaveAccountBalance> balances = leaveAccountRepository.findBalancesByEmployeeId(employeeId, asOf);

        // 발생분별 오류를 다른 발생분으로 상쇄하지 않고 요청 전체 거부
        List<MyLeaveBalanceResponse> response = new ArrayList<>();
        for (LeaveAccountBalance balance : balances) {
            if (balance.getInvalidGrant()) {
                throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
            }
            response.add(convertBalance(balance));
        }
        return response;
    }

    private MyLeaveBalanceResponse convertBalance(LeaveAccountBalance balance) {
        LeaveBalanceAmounts amounts = LeaveBalanceAmounts.from(balance.getQuantityMinutes(), balance.getReservedMinutes());
        return new MyLeaveBalanceResponse(
                balance.getAccountId(), balance.getBalanceTypeId(), balance.getBalanceTypeName(),
                amounts.quantityMinutes(), amounts.reservedMinutes(), amounts.remainingMinutes()
        );
    }
}
