package com.ssafy.leaveservice.domain.ledger.service;

import com.ssafy.leaveservice.domain.ledger.dto.MyLeaveLedgerResponse;
import com.ssafy.leaveservice.domain.ledger.entity.LeaveLedger;
import com.ssafy.leaveservice.domain.ledger.repository.LeaveLedgerRepository;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MyLeaveLedgerService {

    private final LeaveLedgerRepository leaveLedgerRepository;
    private final EmployeeAccess employeeAccess;

    @Transactional(readOnly = true)
    public List<MyLeaveLedgerResponse> getMyLedger() {
        // 현재 로그인 직원 확인
        Long employeeId = employeeAccess.currentEmployeeId();
        if (employeeId == null || employeeId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        return findLedger(employeeId);
    }

    @Transactional(readOnly = true)
    @com.ssafy.leaveservice.global.auth.LeaveAdministratorOnly
    public List<MyLeaveLedgerResponse> getEmployeeLedger(Long employeeId) {
        // 관리자 권한 확인
        Long adminEmployeeId = employeeAccess.currentEmployeeId();
        if (!employeeAccess.isAdmin(adminEmployeeId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        // 조회 대상 직원 ID 검증
        if (employeeId == null || employeeId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        // 본인 조회와 동일한 원본 원장 반환
        return findLedger(employeeId);
    }

    private List<MyLeaveLedgerResponse> findLedger(Long employeeId) {
        // 대상 직원 전체 계정의 원장과 잔액 유형 조회
        List<LeaveLedger> ledgerEntries = leaveLedgerRepository.findAllWithAccountAndBalanceTypeByEmployeeId(employeeId);

        // 합산이나 부호 변경 없이 원본 기록 반환
        List<MyLeaveLedgerResponse> response = ledgerEntries.stream()
                .map(MyLeaveLedgerResponse::from)
                .toList();
        return response;
    }
}
