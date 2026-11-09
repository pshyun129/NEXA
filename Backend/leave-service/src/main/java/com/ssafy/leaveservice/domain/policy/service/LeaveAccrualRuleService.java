package com.ssafy.leaveservice.domain.policy.service;

import com.ssafy.leaveservice.domain.policy.dto.LeaveAccrualRuleResponse;
import com.ssafy.leaveservice.domain.policy.dto.LeaveAccrualRuleReplaceRequest;
import com.ssafy.leaveservice.domain.policy.entity.LeaveAccrualRuleSettings;
import com.ssafy.leaveservice.domain.policy.entity.LeaveAccrualRule;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyGroup;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyVersion;
import com.ssafy.leaveservice.domain.policy.repository.LeaveAccrualRuleRepository;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyGroupRepository;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyVersionRepository;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@com.ssafy.leaveservice.global.auth.LeaveAdministratorOnly
@Service
@RequiredArgsConstructor
public class LeaveAccrualRuleService {

    private final LeavePolicyGroupRepository leavePolicyGroupRepository;
    private final LeavePolicyVersionRepository leavePolicyVersionRepository;
    private final LeaveAccrualRuleRepository leaveAccrualRuleRepository;
    private final EmployeeAccess employeeAccess;
    private final Validator validator;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<LeaveAccrualRuleResponse> replaceAccrualRules(
            Long policyId,
            Long versionId,
            LeaveAccrualRuleReplaceRequest request
    ) {
        // 관리자 권한 확인
        Long employeeId = employeeAccess.currentEmployeeId();
        if (!employeeAccess.isAdmin(employeeId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        // 그룹 → 버전 → 발생 규칙 순서로 잠금
        LeavePolicyGroup policyGroup = leavePolicyGroupRepository.findByIdForUpdate(policyId).orElseThrow(
                () -> new BusinessException(ErrorCode.LEAVE_POLICY_GROUP_NOT_FOUND)
        );
        // 기존 DELETE의 FOR UPDATE 조회를 재사용해 새 FK 참조도 잠금에서 대기시킨다.
        LeavePolicyVersion policyVersion = leavePolicyVersionRepository
                .findByIdAndPolicyIdForDelete(versionId, policyId).orElseThrow(
                        () -> new BusinessException(ErrorCode.LEAVE_POLICY_VERSION_NOT_FOUND)
                );
        List<LeaveAccrualRule> existingRules = leaveAccrualRuleRepository.findAllByPolicyVersionIdForUpdate(versionId);

        // 전체 입력 검증과 ID 매칭을 완료하기 전에는 Entity를 변경하지 않음
        if (!validator.validate(request).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        List<RuleChange> changes = prepareReplacement(existingRules, request.rules());
        Set<Long> retainedRuleIds = new HashSet<>();
        boolean rulesChanged = false;
        for (RuleChange change : changes) {
            if (change.existingRule() == null) {
                rulesChanged = true;
                continue;
            }
            retainedRuleIds.add(change.existingRule().getRuleId());
            if (!change.existingRule().getSettings().equals(change.settings())) {
                rulesChanged = true;
            }
        }
        List<LeaveAccrualRule> removedRules = new ArrayList<>();
        for (LeaveAccrualRule rule : existingRules) {
            if (!retainedRuleIds.contains(rule.getRuleId())) {
                removedRules.add(rule);
            }
        }
        rulesChanged = rulesChanged || !removedRules.isEmpty();

        // 실제 변경이 있을 때만 활성 상태와 전체 사용 이력으로 보호
        boolean isActive = Objects.equals(policyGroup.getActivePolicyVersionId(), policyVersion.getPolicyVersionId());
        if (rulesChanged && (isActive || leavePolicyVersionRepository.existsUsageHistory(versionId))) {
            throw new BusinessException(ErrorCode.LEAVE_POLICY_VERSION_IN_USE);
        }

        // 변경된 Entity만 갱신하고 신규 추가·제외 삭제를 같은 트랜잭션으로 반영
        List<LeaveAccrualRule> finalRules = existingRules;
        if (rulesChanged) {
            List<LeaveAccrualRule> newRules = new ArrayList<>();
            for (RuleChange change : changes) {
                if (change.existingRule() == null) {
                    newRules.add(new LeaveAccrualRule(policyVersion, change.settings()));
                } else if (!change.existingRule().getSettings().equals(change.settings())) {
                    change.existingRule().updateSettings(change.settings());
                }
            }
            leaveAccrualRuleRepository.saveAll(newRules);
            leaveAccrualRuleRepository.deleteAll(removedRules);
            leaveAccrualRuleRepository.flush();
            finalRules = leaveAccrualRuleRepository.findAllByPolicyVersionPolicyVersionIdOrderByRuleIdAsc(versionId);
        }

        // 순서가 아닌 최종 DB 상태를 ID 오름차순으로 반환
        List<LeaveAccrualRuleResponse> response = finalRules.stream()
                .map(rule -> LeaveAccrualRuleResponse.from(rule, versionId))
                .toList();
        return response;
    }

    private List<RuleChange> prepareReplacement(
            List<LeaveAccrualRule> existingRules,
            List<LeaveAccrualRuleReplaceRequest.Rule> requestedRules
    ) {
        Map<Long, LeaveAccrualRule> existingById = new HashMap<>();
        for (LeaveAccrualRule rule : existingRules) {
            existingById.put(rule.getRuleId(), rule);
        }

        Set<Long> explicitRuleIds = new HashSet<>();
        Set<LeaveAccrualRuleSettings> requestedSettings = new HashSet<>();
        for (LeaveAccrualRuleReplaceRequest.Rule requestedRule : requestedRules) {
            if (!requestedSettings.add(requestedRule.toSettings())) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "완전히 동일한 발생 규칙을 중복 지정할 수 없습니다.");
            }
            Long ruleId = requestedRule.ruleId();
            if (ruleId == null) {
                continue;
            }
            if (!explicitRuleIds.add(ruleId)) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "동일한 ruleId를 중복 지정할 수 없습니다.");
            }
            if (!existingById.containsKey(ruleId)) {
                throw new BusinessException(ErrorCode.LEAVE_ACCRUAL_RULE_NOT_FOUND);
            }
        }

        // 명시된 ID를 먼저 예약하여 요청 순서에 따라 ID 없는 항목에 매칭되지 않게 한다.
        Map<LeaveAccrualRuleSettings, List<LeaveAccrualRule>> availableBySettings = new HashMap<>();
        for (LeaveAccrualRule rule : existingRules) {
            if (!explicitRuleIds.contains(rule.getRuleId())) {
                availableBySettings.computeIfAbsent(rule.getSettings(), settings -> new ArrayList<>()).add(rule);
            }
        }

        List<RuleChange> changes = new ArrayList<>();
        for (LeaveAccrualRuleReplaceRequest.Rule requestedRule : requestedRules) {
            LeaveAccrualRuleSettings settings = requestedRule.toSettings();
            LeaveAccrualRule existingRule = null;
            if (requestedRule.ruleId() != null) {
                existingRule = existingById.get(requestedRule.ruleId());
            } else {
                List<LeaveAccrualRule> candidates = availableBySettings.getOrDefault(settings, List.of());
                if (candidates.size() > 1) {
                    throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION,
                            "동일한 기존 규칙이 여러 개입니다. ruleId를 명시해주세요.");
                }
                if (!candidates.isEmpty()) {
                    existingRule = candidates.getFirst();
                    availableBySettings.remove(settings);
                }
            }
            changes.add(new RuleChange(existingRule, settings));
        }
        return changes;
    }

    private record RuleChange(LeaveAccrualRule existingRule, LeaveAccrualRuleSettings settings) {
    }

    @Transactional(readOnly = true)
    public List<LeaveAccrualRuleResponse> getAccrualRules(Long policyId, Long versionId) {
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

        // 활성·사용 여부와 관계없이 해당 버전의 모든 발생 규칙 조회
        Long policyVersionId = policyVersion.getPolicyVersionId();
        List<LeaveAccrualRule> accrualRules = leaveAccrualRuleRepository
                .findAllByPolicyVersionPolicyVersionIdOrderByRuleIdAsc(policyVersionId);

        // 저장된 설정값으로 응답 생성
        List<LeaveAccrualRuleResponse> response = accrualRules.stream()
                .map(rule -> LeaveAccrualRuleResponse.from(rule, policyVersionId))
                .toList();
        return response;
    }
}
