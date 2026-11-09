package com.ssafy.leaveservice.domain.policy.service;

import com.ssafy.leaveservice.domain.policy.dto.MyLeavePolicyResponse;
import com.ssafy.leaveservice.domain.policy.repository.EmployeeLeavePolicyAssignmentRepository;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MyLeavePolicyService {

    private final EmployeeLeavePolicyAssignmentRepository employeeLeavePolicyAssignmentRepository;
    private final EmployeeAccess employeeAccess;

    @Transactional(readOnly = true)
    public MyLeavePolicyResponse getMyPolicy() {
        // 현재 로그인 직원 확인
        Long employeeId = employeeAccess.currentEmployeeId();
        if (employeeId == null || employeeId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        // 한 번 확정한 시각으로 현재 배정과 활성 버전을 함께 조회
        OffsetDateTime currentTime = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        return findPolicy(employeeId, currentTime);
    }

    @Transactional(readOnly = true)
    public MyLeavePolicyResponse getMyPolicyAt(OffsetDateTime asOf) {
        // Preview와 동일한 기준 시각을 사용하되 본인 인증은 유지
        Long employeeId = employeeAccess.currentEmployeeId();
        if (employeeId == null || employeeId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return findPolicy(employeeId, asOf);
    }

    private MyLeavePolicyResponse findPolicy(Long employeeId, OffsetDateTime currentTime) {
        List<MyLeavePolicyResponse> policies = employeeLeavePolicyAssignmentRepository
                .findApplicablePolicies(employeeId, currentTime);

        // 배정 누락과 중복을 구분해 확인
        if (policies.isEmpty()) {
            throw new BusinessException(ErrorCode.CURRENT_LEAVE_POLICY_NOT_FOUND);
        }
        if (policies.size() > 1) {
            throw new BusinessException(ErrorCode.CURRENT_LEAVE_POLICY_CONFLICT);
        }

        // 배정 그룹과 같은 그룹 소속 활성 버전의 존재 확인
        MyLeavePolicyResponse response = policies.getFirst();
        if (response.policyId() == null || response.policyVersionId() == null) {
            throw new BusinessException(ErrorCode.CURRENT_LEAVE_POLICY_CONFLICT);
        }

        // 현재 적용 정책의 기본 설정 반환
        return response;
    }
}
