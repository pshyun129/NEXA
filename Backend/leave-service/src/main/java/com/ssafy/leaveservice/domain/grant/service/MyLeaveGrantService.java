package com.ssafy.leaveservice.domain.grant.service;

import com.ssafy.leaveservice.domain.grant.dto.MyLeaveGrantResponse;
import com.ssafy.leaveservice.domain.grant.entity.LeaveGrant;
import com.ssafy.leaveservice.domain.grant.repository.LeaveGrantRepository;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MyLeaveGrantService {

    private final LeaveGrantRepository leaveGrantRepository;
    private final EmployeeAccess employeeAccess;

    @Transactional(readOnly = true)
    public List<MyLeaveGrantResponse> getMyGrants() {
        // 현재 로그인 직원 확인
        Long employeeId = employeeAccess.currentEmployeeId();
        if (employeeId == null || employeeId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        return findGrants(employeeId);
    }

    @Transactional(readOnly = true)
    @com.ssafy.leaveservice.global.auth.LeaveAdministratorOnly
    public List<MyLeaveGrantResponse> getEmployeeGrants(Long employeeId) {
        // 관리자 권한 확인
        Long adminEmployeeId = employeeAccess.currentEmployeeId();
        if (!employeeAccess.isAdmin(adminEmployeeId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        // 조회 대상 직원 ID 검증
        if (employeeId == null || employeeId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        // 본인 조회와 동일한 원본 발생 이력 반환
        return findGrants(employeeId);
    }

    private List<MyLeaveGrantResponse> findGrants(Long employeeId) {
        // 계정의 HR 직원 ID로 전체 발생 기록과 잔액 유형 조회
        List<LeaveGrant> grants = leaveGrantRepository.findAllWithAccountAndBalanceTypeByEmployeeId(employeeId);

        // 정책·유효기간·원장과 무관하게 원래 발생 수량 반환
        List<MyLeaveGrantResponse> response = grants.stream()
                .map(MyLeaveGrantResponse::from)
                .toList();
        return response;
    }
}
