package com.ssafy.leaveservice.domain.policy;

import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyGroup;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyVersion;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyGroupRepository;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyTypeRuleRepository;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyVersionRepository;
import com.ssafy.leaveservice.domain.policy.service.LeavePolicyTypeRuleService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import com.ssafy.leaveservice.domain.leavetype.repository.LeaveTypeRepository;
import jakarta.validation.Validator;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LeavePolicyTypeRuleServiceTest {

    private final LeavePolicyGroupRepository groupRepository = mock(LeavePolicyGroupRepository.class);
    private final LeavePolicyVersionRepository versionRepository = mock(LeavePolicyVersionRepository.class);
    private final LeavePolicyTypeRuleRepository ruleRepository = mock(LeavePolicyTypeRuleRepository.class);
    private final EmployeeAccess employeeAccess = mock(EmployeeAccess.class);
    private final LeavePolicyTypeRuleService service = new LeavePolicyTypeRuleService(
            groupRepository, versionRepository, ruleRepository, employeeAccess, mock(LeaveTypeRepository.class), mock(Validator.class)
    );
    private final LeavePolicyGroup policyGroup = mock(LeavePolicyGroup.class);
    private final LeavePolicyVersion policyVersion = mock(LeavePolicyVersion.class);

    @BeforeEach
    void setUp() {
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        when(groupRepository.findById(1L)).thenReturn(Optional.of(policyGroup));
        when(policyGroup.getPolicyId()).thenReturn(1L);
        when(versionRepository.findByPolicyVersionIdAndPolicyGroupPolicyId(14L, 1L))
                .thenReturn(Optional.of(policyVersion));
        when(policyVersion.getPolicyVersionId()).thenReturn(14L);
    }

    @Test
    void verifiesMembershipThenQueriesRulesWithoutLocksOrUsageChecks() {
        when(ruleRepository.findAllByPolicyVersionIdOrderByLeaveTypeIdAsc(14L)).thenReturn(List.of());

        assertThat(service.getTypeRules(1L, 14L)).isEmpty();

        InOrder order = inOrder(groupRepository, versionRepository, ruleRepository);
        order.verify(groupRepository).findById(1L);
        order.verify(versionRepository).findByPolicyVersionIdAndPolicyGroupPolicyId(14L, 1L);
        order.verify(ruleRepository).findAllByPolicyVersionIdOrderByLeaveTypeIdAsc(14L);
        verifyNoMoreInteractions(groupRepository, versionRepository, ruleRepository);
    }

    @Test
    void rejectsMissingGroupBeforeVersionAndRuleQueries() {
        when(groupRepository.findById(1L)).thenReturn(Optional.empty());

        assertError(ErrorCode.LEAVE_POLICY_GROUP_NOT_FOUND);

        verifyNoInteractions(versionRepository, ruleRepository);
    }

    @Test
    void rejectsMissingOrForeignVersionBeforeRuleQuery() {
        when(versionRepository.findByPolicyVersionIdAndPolicyGroupPolicyId(14L, 1L)).thenReturn(Optional.empty());

        assertError(ErrorCode.LEAVE_POLICY_VERSION_NOT_FOUND);

        verifyNoInteractions(ruleRepository);
    }

    @Test
    void deniesNonAdminBeforeDatabaseQueries() {
        when(employeeAccess.isAdmin(42L)).thenReturn(false);

        assertError(ErrorCode.ACCESS_DENIED);

        verifyNoInteractions(groupRepository, versionRepository, ruleRepository);
    }

    @Test
    void preservesUnavailableAuthenticationBeforeDatabaseQueries() {
        when(employeeAccess.currentEmployeeId()).thenThrow(new BusinessException(ErrorCode.AUTHENTICATION_UNAVAILABLE));

        assertError(ErrorCode.AUTHENTICATION_UNAVAILABLE);

        verifyNoInteractions(groupRepository, versionRepository, ruleRepository);
    }

    private void assertError(ErrorCode errorCode) {
        assertThatThrownBy(() -> service.getTypeRules(1L, 14L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
