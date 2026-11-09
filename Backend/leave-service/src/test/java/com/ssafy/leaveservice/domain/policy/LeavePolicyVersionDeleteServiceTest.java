package com.ssafy.leaveservice.domain.policy;

import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyGroup;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyVersion;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyGroupRepository;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyVersionRepository;
import com.ssafy.leaveservice.domain.policy.service.LeavePolicyVersionService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LeavePolicyVersionDeleteServiceTest {

    private final LeavePolicyGroupRepository groupRepository = mock(LeavePolicyGroupRepository.class);
    private final LeavePolicyVersionRepository versionRepository = mock(LeavePolicyVersionRepository.class);
    private final EmployeeAccess employeeAccess = mock(EmployeeAccess.class);
    private final LeavePolicyVersionService service = new LeavePolicyVersionService(
            groupRepository, versionRepository, employeeAccess, mock(Validator.class)
    );
    private final LeavePolicyGroup policyGroup = mock(LeavePolicyGroup.class);
    private final LeavePolicyVersion policyVersion = mock(LeavePolicyVersion.class);

    @BeforeEach
    void setUp() {
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        when(groupRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(policyGroup));
        when(versionRepository.findByIdAndPolicyIdForDelete(13L, 1L)).thenReturn(Optional.of(policyVersion));
        when(policyVersion.getPolicyVersionId()).thenReturn(13L);
    }

    @Test
    void locksAndRechecksBeforeDeletingChildrenThenVersion() {
        service.deletePolicyVersion(1L, 13L);

        InOrder order = inOrder(groupRepository, versionRepository);
        order.verify(groupRepository).findByIdForUpdate(1L);
        order.verify(versionRepository).findByIdAndPolicyIdForDelete(13L, 1L);
        order.verify(versionRepository).existsUsageHistory(13L);
        order.verify(versionRepository).lockAccrualRules(13L);
        order.verify(versionRepository).lockTypeRules(13L);
        order.verify(versionRepository).existsUsageHistory(13L);
        order.verify(versionRepository).deleteTypeRules(13L);
        order.verify(versionRepository).deleteAccrualRules(13L);
        order.verify(versionRepository).delete(policyVersion);
        order.verify(versionRepository).flush();
    }

    @Test
    void protectsActiveVersionBeforeTouchingRules() {
        when(policyGroup.getActivePolicyVersionId()).thenReturn(13L);

        assertError(ErrorCode.LEAVE_POLICY_VERSION_IN_USE);

        verify(versionRepository, never()).lockAccrualRules(anyLong());
        verify(versionRepository, never()).delete(any());
    }

    @Test
    void protectsUsedVersionBeforeTouchingRules() {
        when(versionRepository.existsUsageHistory(13L)).thenReturn(true);

        assertError(ErrorCode.LEAVE_POLICY_VERSION_IN_USE);

        verify(versionRepository, never()).lockAccrualRules(anyLong());
        verify(versionRepository, never()).deleteTypeRules(anyLong());
    }

    @Test
    void rechecksReferencesAfterRuleLocksBeforeAnyDeletion() {
        when(versionRepository.existsUsageHistory(13L)).thenReturn(false, true);

        assertError(ErrorCode.LEAVE_POLICY_VERSION_IN_USE);

        verify(versionRepository).lockAccrualRules(13L);
        verify(versionRepository).lockTypeRules(13L);
        verify(versionRepository, never()).deleteTypeRules(anyLong());
        verify(versionRepository, never()).deleteAccrualRules(anyLong());
        verify(versionRepository, never()).delete(any());
    }

    @Test
    void rejectsMissingGroupBeforeVersionQuery() {
        when(groupRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        assertError(ErrorCode.LEAVE_POLICY_GROUP_NOT_FOUND);

        verifyNoInteractions(versionRepository);
    }

    @Test
    void rejectsMissingOrOtherGroupVersionBeforeRuleQueries() {
        when(versionRepository.findByIdAndPolicyIdForDelete(13L, 1L)).thenReturn(Optional.empty());

        assertError(ErrorCode.LEAVE_POLICY_VERSION_NOT_FOUND);

        verify(versionRepository, never()).existsUsageHistory(anyLong());
    }

    @Test
    void rejectsNonAdminBeforeTakingLocks() {
        when(employeeAccess.isAdmin(42L)).thenReturn(false);

        assertError(ErrorCode.ACCESS_DENIED);

        verifyNoInteractions(groupRepository, versionRepository);
    }

    private void assertError(ErrorCode errorCode) {
        assertThatThrownBy(() -> service.deletePolicyVersion(1L, 13L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
