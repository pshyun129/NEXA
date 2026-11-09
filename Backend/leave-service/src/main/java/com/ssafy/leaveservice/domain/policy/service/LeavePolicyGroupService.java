package com.ssafy.leaveservice.domain.policy.service;

import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyGroupResponse;
import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyGroupCreateRequest;
import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyGroupUpdateRequest;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyGroup;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyGroupRepository;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@com.ssafy.leaveservice.global.auth.LeaveAdministratorOnly
@Service
@RequiredArgsConstructor
public class LeavePolicyGroupService {

    private final LeavePolicyGroupRepository leavePolicyGroupRepository;
    private final EmployeeAccess employeeAccess;

    @Transactional(readOnly = true)
    public List<LeavePolicyGroupResponse> getAllPolicies() {
        Long employeeId = employeeAccess.currentEmployeeId();
        if (!employeeAccess.isAdmin(employeeId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        List<LeavePolicyGroup> policyGroups = leavePolicyGroupRepository.findAllByOrderByPolicyIdAsc();

        List<LeavePolicyGroupResponse> response = policyGroups.stream()
                .map(LeavePolicyGroupResponse::from)
                .toList();
        return response;
    }

    @Transactional(readOnly = true)
    public LeavePolicyGroupResponse getPolicyGroup(Long policyId) {
        Long employeeId = employeeAccess.currentEmployeeId();
        if (!employeeAccess.isAdmin(employeeId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        LeavePolicyGroup policyGroup = leavePolicyGroupRepository.findById(policyId).orElseThrow(
                () -> new BusinessException(ErrorCode.LEAVE_POLICY_GROUP_NOT_FOUND)
        );

        LeavePolicyGroupResponse response = LeavePolicyGroupResponse.from(policyGroup);
        return response;
    }

    @Transactional
    public LeavePolicyGroupResponse createPolicyGroup(LeavePolicyGroupCreateRequest request) {
        Long employeeId = employeeAccess.currentEmployeeId();
        if (!employeeAccess.isAdmin(employeeId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        // 정책 버전 생성과 직원 배정은 별도 API의 책임이므로 그룹만 저장한다.
        LeavePolicyGroup policyGroup = new LeavePolicyGroup(request.name());
        LeavePolicyGroup savedPolicyGroup = leavePolicyGroupRepository.save(policyGroup);

        LeavePolicyGroupResponse response = LeavePolicyGroupResponse.from(savedPolicyGroup);
        return response;
    }

    @Transactional
    public LeavePolicyGroupResponse updatePolicyGroup(Long policyId, LeavePolicyGroupUpdateRequest request) {
        Long employeeId = employeeAccess.currentEmployeeId();
        if (!employeeAccess.isAdmin(employeeId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        LeavePolicyGroup policyGroup = leavePolicyGroupRepository.findById(policyId).orElseThrow(
                () -> new BusinessException(ErrorCode.LEAVE_POLICY_GROUP_NOT_FOUND)
        );

        // 이름은 관리용 표시 정보다. 연결된 정책 버전이나 직원 배정을 변경하지 않는다.
        policyGroup.updateName(request.name());

        LeavePolicyGroupResponse response = LeavePolicyGroupResponse.from(policyGroup);
        return response;
    }
}
