package com.ssafy.leaveservice.domain.policy.service;

import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyTypeRuleResponse;
import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyTypeRuleUpsertRequest;
import com.ssafy.leaveservice.domain.leavetype.entity.LeaveType;
import com.ssafy.leaveservice.domain.leavetype.repository.LeaveTypeRepository;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyGroup;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyTypeRule;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyVersion;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyGroupRepository;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyTypeRuleRepository;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyVersionRepository;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.util.List;
import java.util.Objects;

@com.ssafy.leaveservice.global.auth.LeaveAdministratorOnly
@Service
@RequiredArgsConstructor
public class LeavePolicyTypeRuleService {

    private final LeavePolicyGroupRepository leavePolicyGroupRepository;
    private final LeavePolicyVersionRepository leavePolicyVersionRepository;
    private final LeavePolicyTypeRuleRepository leavePolicyTypeRuleRepository;
    private final EmployeeAccess employeeAccess;
    private final LeaveTypeRepository leaveTypeRepository;
    private final Validator validator;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LeavePolicyTypeRuleResponse upsertTypeRule(
            Long policyId,
            Long versionId,
            Long leaveTypeId,
            LeavePolicyTypeRuleUpsertRequest request
    ) {
        // 관리자 권한 확인
        Long employeeId = employeeAccess.currentEmployeeId();
        if (!employeeAccess.isAdmin(employeeId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        // 그룹 → 버전 → 휴가 유형 → 규칙 순서로 잠금
        LeavePolicyGroup policyGroup = leavePolicyGroupRepository.findByIdForUpdate(policyId).orElseThrow(
                () -> new BusinessException(ErrorCode.LEAVE_POLICY_GROUP_NOT_FOUND)
        );
        leavePolicyVersionRepository.findByIdAndPolicyIdForDelete(versionId, policyId).orElseThrow(
                () -> new BusinessException(ErrorCode.LEAVE_POLICY_VERSION_NOT_FOUND)
        );
        LeaveType leaveType = leaveTypeRepository.findByIdForUpdate(leaveTypeId).orElseThrow(
                () -> new BusinessException(ErrorCode.LEAVE_TYPE_NOT_FOUND)
        );
        LeavePolicyTypeRule existingRule = leavePolicyTypeRuleRepository
                .findByVersionIdAndLeaveTypeIdForUpdate(versionId, leaveTypeId).orElse(null);

        // 전체 설정과 잠금 후 읽은 마스터의 신청 단위·총 시간 상한 검증
        if (!validator.validate(request).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        validateMasterSettings(leaveType, request);

        // 실제 변경이 있는 경우에만 활성·과거 사용 버전 보호
        boolean settingsChanged = existingRule == null
                || existingRule.isRequestable() != request.isRequestable()
                || existingRule.getAllowedUnits() != request.allowedUnits()
                || !Objects.equals(existingRule.getMinMinutes(), request.minMinutes())
                || !Objects.equals(existingRule.getMaxMinutes(), request.maxMinutes());
        if (settingsChanged) {
            boolean isActive = Objects.equals(policyGroup.getActivePolicyVersionId(), versionId);
            if (isActive || leavePolicyVersionRepository.existsUsageHistory(versionId)) {
                throw new BusinessException(ErrorCode.LEAVE_POLICY_VERSION_IN_USE);
            }
        }

        // 신규 조합만 INSERT하고 기존 규칙은 ID를 유지한 채 변경 감지로 수정
        LeavePolicyTypeRule resultRule = existingRule;
        if (existingRule == null) {
            LeavePolicyTypeRule newRule = new LeavePolicyTypeRule(
                    versionId,
                    leaveTypeId,
                    request.isRequestable(),
                    request.allowedUnits(),
                    request.minMinutes(),
                    request.maxMinutes()
            );
            resultRule = leavePolicyTypeRuleRepository.save(newRule);
        } else if (settingsChanged) {
            existingRule.updateSettings(
                    request.isRequestable(),
                    request.allowedUnits(),
                    request.minMinutes(),
                    request.maxMinutes()
            );
        }
        if (settingsChanged) {
            leavePolicyTypeRuleRepository.flush();
        }

        // 실제 저장된 규칙의 상세 응답 반환
        LeavePolicyTypeRuleResponse response = LeavePolicyTypeRuleResponse.from(resultRule);
        return response;
    }

    private void validateMasterSettings(LeaveType leaveType, LeavePolicyTypeRuleUpsertRequest request) {
        boolean unitsCompatible = request.allowedUnits().isCompatibleWith(leaveType.getRequestUnit());
        if (!unitsCompatible) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "정책 신청 단위는 휴가 유형의 신청 단위를 확장할 수 없습니다.");
        }

        Integer masterMaxMinutes = leaveType.getMaxMinutes();
        if (masterMaxMinutes != null
                && ((request.minMinutes() != null && request.minMinutes() > masterMaxMinutes)
                    || (request.maxMinutes() != null && request.maxMinutes() > masterMaxMinutes))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "정책 시간 제한은 휴가 유형의 총 신청 시간 상한을 초과할 수 없습니다.");
        }
    }

    @Transactional(readOnly = true)
    public List<LeavePolicyTypeRuleResponse> getTypeRules(Long policyId, Long versionId) {
        // 관리자 권한 확인
        Long employeeId = employeeAccess.currentEmployeeId();
        if (!employeeAccess.isAdmin(employeeId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        // 정책 그룹과 버전 소속 확인
        LeavePolicyGroup policyGroup = leavePolicyGroupRepository.findById(policyId).orElseThrow(
                () -> new BusinessException(ErrorCode.LEAVE_POLICY_GROUP_NOT_FOUND)
        );
        LeavePolicyVersion policyVersion = leavePolicyVersionRepository
                .findByPolicyVersionIdAndPolicyGroupPolicyId(versionId, policyGroup.getPolicyId()).orElseThrow(
                        () -> new BusinessException(ErrorCode.LEAVE_POLICY_VERSION_NOT_FOUND)
                );

        // 활성·사용 여부와 무관하게 실제 등록된 규칙만 휴가 유형 ID 순으로 조회
        List<LeavePolicyTypeRule> typeRules = leavePolicyTypeRuleRepository
                .findAllByPolicyVersionIdOrderByLeaveTypeIdAsc(policyVersion.getPolicyVersionId());

        // 규칙 테이블의 전체 설정값으로 응답 생성
        List<LeavePolicyTypeRuleResponse> response = typeRules.stream()
                .map(LeavePolicyTypeRuleResponse::from)
                .toList();
        return response;
    }
}
