package com.ssafy.leaveservice.domain.policy;

import com.ssafy.leaveservice.domain.policy.dto.LeaveAccrualRuleReplaceRequest;
import com.ssafy.leaveservice.domain.policy.entity.AccrualFrequency;
import com.ssafy.leaveservice.domain.policy.entity.LeaveAccrualRule;
import com.ssafy.leaveservice.domain.policy.entity.LeaveAccrualRuleSettings;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyGroup;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyVersion;
import com.ssafy.leaveservice.domain.policy.repository.LeaveAccrualRuleRepository;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyGroupRepository;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyVersionRepository;
import com.ssafy.leaveservice.domain.policy.service.LeaveAccrualRuleService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LeaveAccrualRuleReplaceServiceTest {

    private final LeavePolicyGroupRepository groupRepository = mock(LeavePolicyGroupRepository.class);
    private final LeavePolicyVersionRepository versionRepository = mock(LeavePolicyVersionRepository.class);
    private final LeaveAccrualRuleRepository ruleRepository = mock(LeaveAccrualRuleRepository.class);
    private final EmployeeAccess employeeAccess = mock(EmployeeAccess.class);
    private final LeaveAccrualRuleService service = new LeaveAccrualRuleService(
            groupRepository, versionRepository, ruleRepository, employeeAccess, mock(Validator.class)
    );
    private final LeavePolicyGroup policyGroup = mock(LeavePolicyGroup.class);
    private final LeavePolicyVersion policyVersion = mock(LeavePolicyVersion.class);
    private final LeaveAccrualRule existingRule = spy(new LeaveAccrualRule(policyVersion,
            new LeaveAccrualRuleSettings(0, 11, AccrualFrequency.MONTHLY, 480, true)));

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(existingRule, "ruleId", 40L);
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        when(groupRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(policyGroup));
        when(versionRepository.findByIdAndPolicyIdForDelete(14L, 1L)).thenReturn(Optional.of(policyVersion));
        when(policyVersion.getPolicyVersionId()).thenReturn(14L);
        when(ruleRepository.findAllByPolicyVersionIdForUpdate(14L)).thenReturn(List.of(existingRule));
    }

    @Test
    void idlessSameSettingsOnActiveVersionDoesNotIssueMutationsOrUsageQuery() {
        when(policyGroup.getActivePolicyVersionId()).thenReturn(14L);

        assertThat(service.replaceAccrualRules(1L, 14L, request(null, 480)).getFirst().ruleId()).isEqualTo(40L);

        verify(existingRule, never()).updateSettings(any());
        verify(versionRepository, never()).existsUsageHistory(anyLong());
        verify(ruleRepository, never()).saveAll(any());
        verify(ruleRepository, never()).deleteAll(org.mockito.ArgumentMatchers.<LeaveAccrualRule>anyIterable());
        verify(ruleRepository, never()).flush();
    }

    @Test
    void takesGroupVersionRuleLocksBeforeUpdatingExistingEntity() {
        when(ruleRepository.findAllByPolicyVersionPolicyVersionIdOrderByRuleIdAsc(14L)).thenReturn(List.of(existingRule));

        assertThat(service.replaceAccrualRules(1L, 14L, request(40L, 17)).getFirst().grantMinutes()).isEqualTo(17);

        InOrder order = inOrder(groupRepository, versionRepository, ruleRepository, existingRule);
        order.verify(groupRepository).findByIdForUpdate(1L);
        order.verify(versionRepository).findByIdAndPolicyIdForDelete(14L, 1L);
        order.verify(ruleRepository).findAllByPolicyVersionIdForUpdate(14L);
        order.verify(versionRepository).existsUsageHistory(14L);
        order.verify(existingRule).updateSettings(any());
        assertThat(existingRule.getRuleId()).isEqualTo(40L);
    }

    @Test
    void validatesAllIdsBeforeChangingAnEarlierValidRule() {
        LeaveAccrualRuleReplaceRequest request = new LeaveAccrualRuleReplaceRequest(List.of(
                new LeaveAccrualRuleReplaceRequest.Rule(40L, 0, 11, AccrualFrequency.MONTHLY, 17, true),
                new LeaveAccrualRuleReplaceRequest.Rule(9999L, 12, null, AccrualFrequency.YEARLY, 7200, true)
        ));

        assertError(request, ErrorCode.LEAVE_ACCRUAL_RULE_NOT_FOUND);

        verify(existingRule, never()).updateSettings(any());
        verify(ruleRepository, never()).saveAll(any());
    }

    @Test
    void usageConflictDoesNotPartiallyUpdateEntity() {
        when(versionRepository.existsUsageHistory(14L)).thenReturn(true);

        assertError(request(40L, 17), ErrorCode.LEAVE_POLICY_VERSION_IN_USE);

        assertThat(existingRule.getGrantMinutes()).isEqualTo(480);
        verify(existingRule, never()).updateSettings(any());
    }

    @Test
    void rejectsNonAdminBeforeLocks() {
        when(employeeAccess.isAdmin(42L)).thenReturn(false);

        assertError(request(40L, 17), ErrorCode.ACCESS_DENIED);

        verifyNoInteractions(groupRepository, versionRepository, ruleRepository);
    }

    @Test
    void missingGroupStopsBeforeVersionAndRuleQueries() {
        when(groupRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        assertError(request(40L, 17), ErrorCode.LEAVE_POLICY_GROUP_NOT_FOUND);

        verifyNoInteractions(versionRepository, ruleRepository);
    }

    @Test
    void missingOrForeignVersionStopsBeforeRuleQuery() {
        when(versionRepository.findByIdAndPolicyIdForDelete(14L, 1L)).thenReturn(Optional.empty());

        assertError(request(40L, 17), ErrorCode.LEAVE_POLICY_VERSION_NOT_FOUND);

        verifyNoInteractions(ruleRepository);
    }

    private LeaveAccrualRuleReplaceRequest request(Long id, int minutes) {
        return new LeaveAccrualRuleReplaceRequest(List.of(
                new LeaveAccrualRuleReplaceRequest.Rule(id, 0, 11, AccrualFrequency.MONTHLY, minutes, true)
        ));
    }

    private void assertError(LeaveAccrualRuleReplaceRequest request, ErrorCode errorCode) {
        assertThatThrownBy(() -> service.replaceAccrualRules(1L, 14L, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
