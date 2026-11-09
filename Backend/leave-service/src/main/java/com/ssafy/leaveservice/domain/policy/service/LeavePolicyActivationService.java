package com.ssafy.leaveservice.domain.policy.service;

import com.ssafy.leaveservice.domain.leavetype.entity.LeaveType;
import com.ssafy.leaveservice.domain.leavetype.repository.LeaveTypeRepository;
import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyActivationRequest;
import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyActivationResponse;
import com.ssafy.leaveservice.domain.policy.entity.LeaveAccrualRule;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyGroup;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyTypeRule;
import com.ssafy.leaveservice.domain.policy.repository.LeaveAccrualRuleRepository;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyGroupRepository;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyTypeRuleRepository;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyVersionRepository;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@com.ssafy.leaveservice.global.auth.LeaveAdministratorOnly
@Service
@RequiredArgsConstructor
public class LeavePolicyActivationService {

    private final LeavePolicyGroupRepository leavePolicyGroupRepository;
    private final LeavePolicyVersionRepository leavePolicyVersionRepository;
    private final LeavePolicyTypeRuleRepository leavePolicyTypeRuleRepository;
    private final LeaveAccrualRuleRepository leaveAccrualRuleRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final EmployeeAccess employeeAccess;
    private final Validator validator;
    private final EntityManager entityManager;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LeavePolicyActivationResponse activateVersion(Long policyId, LeavePolicyActivationRequest request) {
        // 관리자 권한과 요청값 확인
        Long employeeId = employeeAccess.currentEmployeeId();
        if (!employeeAccess.isAdmin(employeeId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        if (!validator.validate(request).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        Long versionId = request.versionId();

        // 그룹과 소속 버전을 잠가 활성화·설정 변경을 직렬화
        LeavePolicyGroup policyGroup = leavePolicyGroupRepository.findByIdForUpdate(policyId).orElseThrow(
                () -> new BusinessException(ErrorCode.LEAVE_POLICY_GROUP_NOT_FOUND)
        );
        entityManager.refresh(policyGroup);
        leavePolicyVersionRepository.findByIdAndPolicyIdForDelete(versionId, policyId).orElseThrow(
                () -> new BusinessException(ErrorCode.LEAVE_POLICY_VERSION_NOT_FOUND)
        );
        if (Objects.equals(policyGroup.getActivePolicyVersionId(), versionId)) {
            return new LeavePolicyActivationResponse(policyId, versionId);
        }

        // 마스터를 ID 순서로 잠근 뒤 규칙을 잠가 최신 설정으로 검증
        List<LeavePolicyTypeRule> typeRules = leavePolicyTypeRuleRepository
                .findAllByPolicyVersionIdOrderByLeaveTypeIdAsc(versionId);
        Map<Long, LeaveType> leaveTypes = new HashMap<>();
        for (LeavePolicyTypeRule rule : typeRules) {
            Long leaveTypeId = rule.getLeaveTypeId();
            LeaveType leaveType = leaveTypeRepository.findByIdForUpdate(leaveTypeId).orElseThrow(
                    () -> new BusinessException(ErrorCode.LEAVE_POLICY_ACTIVATION_CONFLICT)
            );
            // 같은 영속성 컨텍스트에서 먼저 읽은 마스터도 잠금 이후 값으로 검증한다.
            entityManager.refresh(leaveType);
            leaveTypes.put(leaveTypeId, leaveType);
        }
        typeRules = leavePolicyTypeRuleRepository.findAllByPolicyVersionIdForUpdate(versionId);
        validateRequestableRules(typeRules, leaveTypes);
        List<LeaveAccrualRule> accrualRules = leaveAccrualRuleRepository.findAllByPolicyVersionIdForUpdate(versionId);
        validateAccrualRules(accrualRules);

        // 과거 이력과 규칙은 보존하고 활성 포인터만 변경
        int updatedRows = leavePolicyGroupRepository.updateActiveVersion(policyId, versionId);
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.LEAVE_POLICY_ACTIVATION_CONFLICT);
        }
        entityManager.refresh(policyGroup);

        // DB와 영속성 컨텍스트에 반영된 활성 상태 반환
        LeavePolicyActivationResponse response = new LeavePolicyActivationResponse(
                policyGroup.getPolicyId(),
                policyGroup.getActivePolicyVersionId()
        );
        return response;
    }

    private void validateRequestableRules(List<LeavePolicyTypeRule> typeRules, Map<Long, LeaveType> leaveTypes) {
        int requestableRuleCount = 0;
        for (LeavePolicyTypeRule rule : typeRules) {
            LeaveType leaveType = leaveTypes.get(rule.getLeaveTypeId());
            if (!rule.isRequestable() || !leaveType.isActive()) {
                continue;
            }

            Integer minMinutes = rule.getMinMinutes();
            Integer maxMinutes = rule.getMaxMinutes();
            boolean minuteRangeInvalid = (minMinutes != null && minMinutes <= 0)
                    || (maxMinutes != null && maxMinutes <= 0)
                    || (minMinutes != null && maxMinutes != null && minMinutes > maxMinutes);
            Integer masterMaxMinutes = leaveType.getMaxMinutes();
            boolean exceedsMasterLimit = masterMaxMinutes != null
                    && ((minMinutes != null && minMinutes > masterMaxMinutes)
                        || (maxMinutes != null && maxMinutes > masterMaxMinutes));
            if (rule.getAllowedUnits() == null
                    || !rule.getAllowedUnits().isCompatibleWith(leaveType.getRequestUnit())
                    || minuteRangeInvalid || exceedsMasterLimit) {
                throw new BusinessException(ErrorCode.LEAVE_POLICY_ACTIVATION_CONFLICT);
            }
            requestableRuleCount++;
        }
        if (requestableRuleCount == 0) {
            throw new BusinessException(ErrorCode.LEAVE_POLICY_ACTIVATION_CONFLICT);
        }
    }

    private void validateAccrualRules(List<LeaveAccrualRule> accrualRules) {
        for (LeaveAccrualRule rule : accrualRules) {
            Integer maxServiceMonths = rule.getMaxServiceMonths();
            if (rule.getMinServiceMonths() < 0
                    || (maxServiceMonths != null && maxServiceMonths < rule.getMinServiceMonths())
                    || rule.getGrantMinutes() <= 0 || rule.getFrequency() == null) {
                throw new BusinessException(ErrorCode.LEAVE_POLICY_ACTIVATION_CONFLICT);
            }
        }
    }
}
