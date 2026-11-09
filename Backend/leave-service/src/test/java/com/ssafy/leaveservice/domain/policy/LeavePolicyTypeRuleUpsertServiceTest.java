package com.ssafy.leaveservice.domain.policy;

import com.ssafy.leaveservice.domain.leavetype.entity.LeaveType;
import com.ssafy.leaveservice.domain.leavetype.entity.RequestUnit;
import com.ssafy.leaveservice.domain.leavetype.repository.LeaveTypeRepository;
import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyTypeRuleUpsertRequest;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyGroup;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyTypeRule;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyVersion;
import com.ssafy.leaveservice.domain.policy.entity.PolicyAllowedUnits;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyGroupRepository;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyTypeRuleRepository;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyVersionRepository;
import com.ssafy.leaveservice.domain.policy.service.LeavePolicyTypeRuleService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LeavePolicyTypeRuleUpsertServiceTest {

    private final LeavePolicyGroupRepository groupRepository = mock(LeavePolicyGroupRepository.class);
    private final LeavePolicyVersionRepository versionRepository = mock(LeavePolicyVersionRepository.class);
    private final LeavePolicyTypeRuleRepository ruleRepository = mock(LeavePolicyTypeRuleRepository.class);
    private final LeaveTypeRepository typeRepository = mock(LeaveTypeRepository.class);
    private final EmployeeAccess employeeAccess = mock(EmployeeAccess.class);
    private final LeavePolicyTypeRuleService service = new LeavePolicyTypeRuleService(
            groupRepository, versionRepository, ruleRepository, employeeAccess, typeRepository, mock(Validator.class)
    );
    private final LeavePolicyGroup policyGroup = mock(LeavePolicyGroup.class);
    private final LeaveType leaveType = mock(LeaveType.class);
    private final LeavePolicyTypeRule rule = spy(new LeavePolicyTypeRule(15L, 2L, true, PolicyAllowedUnits.HALF, 30, 60));

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(rule, "ruleId", 40L);
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        when(groupRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(policyGroup));
        when(versionRepository.findByIdAndPolicyIdForDelete(15L, 1L)).thenReturn(Optional.of(mock(LeavePolicyVersion.class)));
        when(typeRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(leaveType));
        when(leaveType.getRequestUnit()).thenReturn(RequestUnit.HALF_DAY);
        when(leaveType.getMaxMinutes()).thenReturn(120);
        when(ruleRepository.findByVersionIdAndLeaveTypeIdForUpdate(15L, 2L)).thenReturn(Optional.of(rule));
    }

    @Test
    void locksGroupVersionTypeThenRuleAndUpdatesWithoutReinserting() {
        service.upsertTypeRule(1L, 15L, 2L, new LeavePolicyTypeRuleUpsertRequest(false, PolicyAllowedUnits.HALF, 30, 90));

        InOrder order = inOrder(groupRepository, versionRepository, typeRepository, ruleRepository, rule);
        order.verify(groupRepository).findByIdForUpdate(1L);
        order.verify(versionRepository).findByIdAndPolicyIdForDelete(15L, 1L);
        order.verify(typeRepository).findByIdForUpdate(2L);
        order.verify(ruleRepository).findByVersionIdAndLeaveTypeIdForUpdate(15L, 2L);
        order.verify(versionRepository).existsUsageHistory(15L);
        order.verify(rule).updateSettings(false, PolicyAllowedUnits.HALF, 30, 90);
        verify(ruleRepository, never()).save(any());
        assertThat(rule.getRuleId()).isEqualTo(40L);
    }

    @Test
    void unchangedActiveVersionDoesNotUpdateOrQueryUsage() {
        when(policyGroup.getActivePolicyVersionId()).thenReturn(15L);

        assertThat(service.upsertTypeRule(1L, 15L, 2L, sameRequest()).ruleId()).isEqualTo(40L);

        verify(rule, never()).updateSettings(anyBoolean(), any(), any(), any());
        verify(ruleRepository, never()).save(any());
        verify(ruleRepository, never()).flush();
        verify(versionRepository, never()).existsUsageHistory(anyLong());
    }

    @Test
    void incompatibleUnitsFailBeforeUsageCheckAndEntityMutation() {
        assertError(new LeavePolicyTypeRuleUpsertRequest(true, PolicyAllowedUnits.FULL, null, null), ErrorCode.INVALID_INPUT);

        verify(versionRepository, never()).existsUsageHistory(anyLong());
        verify(rule, never()).updateSettings(anyBoolean(), any(), any(), any());
    }

    @Test
    void capViolationLeavesEntityUntouched() {
        assertError(new LeavePolicyTypeRuleUpsertRequest(true, PolicyAllowedUnits.HALF, 121, null), ErrorCode.INVALID_INPUT);

        assertThat(rule.getMinMinutes()).isEqualTo(30);
        verify(rule, never()).updateSettings(anyBoolean(), any(), any(), any());
    }

    @Test
    void usedVersionConflictDoesNotPartiallyMutateEntity() {
        when(versionRepository.existsUsageHistory(15L)).thenReturn(true);

        assertError(new LeavePolicyTypeRuleUpsertRequest(false, PolicyAllowedUnits.HALF, null, null), ErrorCode.LEAVE_POLICY_VERSION_IN_USE);

        verify(rule, never()).updateSettings(anyBoolean(), any(), any(), any());
        verify(ruleRepository, never()).save(any());
    }

    @Test
    void newRuleUsesPathIdentityAndDatabaseGeneratedId() {
        when(ruleRepository.findByVersionIdAndLeaveTypeIdForUpdate(15L, 2L)).thenReturn(Optional.empty());
        when(ruleRepository.save(any(LeavePolicyTypeRule.class))).thenAnswer(invocation -> {
            LeavePolicyTypeRule newRule = invocation.getArgument(0);
            ReflectionTestUtils.setField(newRule, "ruleId", 100L);
            return newRule;
        });

        assertThat(service.upsertTypeRule(1L, 15L, 2L, sameRequest()))
                .satisfies(response -> {
                    assertThat(response.ruleId()).isEqualTo(100L);
                    assertThat(response.policyVersionId()).isEqualTo(15L);
                    assertThat(response.leaveTypeId()).isEqualTo(2L);
                });
    }

    @Test
    void rejectsNonAdminBeforeDatabaseQueries() {
        when(employeeAccess.isAdmin(42L)).thenReturn(false);

        assertError(sameRequest(), ErrorCode.ACCESS_DENIED);

        verifyNoInteractions(groupRepository, versionRepository, typeRepository, ruleRepository);
    }

    @Test
    void missingGroupStopsBeforeOtherQueries() {
        when(groupRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        assertError(sameRequest(), ErrorCode.LEAVE_POLICY_GROUP_NOT_FOUND);

        verifyNoInteractions(versionRepository, typeRepository, ruleRepository);
    }

    @Test
    void missingOrForeignVersionStopsBeforeTypeAndRuleQueries() {
        when(versionRepository.findByIdAndPolicyIdForDelete(15L, 1L)).thenReturn(Optional.empty());

        assertError(sameRequest(), ErrorCode.LEAVE_POLICY_VERSION_NOT_FOUND);

        verifyNoInteractions(typeRepository, ruleRepository);
    }

    @Test
    void missingTypeStopsBeforeRuleQuery() {
        when(typeRepository.findByIdForUpdate(2L)).thenReturn(Optional.empty());

        assertError(sameRequest(), ErrorCode.LEAVE_TYPE_NOT_FOUND);

        verifyNoInteractions(ruleRepository);
    }

    private LeavePolicyTypeRuleUpsertRequest sameRequest() {
        return new LeavePolicyTypeRuleUpsertRequest(true, PolicyAllowedUnits.HALF, 30, 60);
    }

    private void assertError(LeavePolicyTypeRuleUpsertRequest request, ErrorCode errorCode) {
        assertThatThrownBy(() -> service.upsertTypeRule(1L, 15L, 2L, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
