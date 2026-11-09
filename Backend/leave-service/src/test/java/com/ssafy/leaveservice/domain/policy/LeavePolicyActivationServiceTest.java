package com.ssafy.leaveservice.domain.policy;

import com.ssafy.leaveservice.domain.leavetype.entity.LeaveType;
import com.ssafy.leaveservice.domain.leavetype.entity.RequestUnit;
import com.ssafy.leaveservice.domain.leavetype.repository.LeaveTypeRepository;
import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyActivationRequest;
import com.ssafy.leaveservice.domain.policy.entity.*;
import com.ssafy.leaveservice.domain.policy.repository.*;
import com.ssafy.leaveservice.domain.policy.service.LeavePolicyActivationService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LeavePolicyActivationServiceTest {

    private final LeavePolicyGroupRepository groupRepository = mock(LeavePolicyGroupRepository.class);
    private final LeavePolicyVersionRepository versionRepository = mock(LeavePolicyVersionRepository.class);
    private final LeavePolicyTypeRuleRepository typeRuleRepository = mock(LeavePolicyTypeRuleRepository.class);
    private final LeaveAccrualRuleRepository accrualRepository = mock(LeaveAccrualRuleRepository.class);
    private final LeaveTypeRepository typeRepository = mock(LeaveTypeRepository.class);
    private final EmployeeAccess employeeAccess = mock(EmployeeAccess.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final LeavePolicyActivationService service = new LeavePolicyActivationService(
            groupRepository, versionRepository, typeRuleRepository, accrualRepository,
            typeRepository, employeeAccess, mock(Validator.class), entityManager
    );
    private final LeavePolicyGroup group = mock(LeavePolicyGroup.class);
    private final LeaveType type = mock(LeaveType.class);
    private final LeavePolicyTypeRule rule = new LeavePolicyTypeRule(15L, 2L, true, PolicyAllowedUnits.FULL, 30, 60);

    @BeforeEach
    void setUp() {
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        when(group.getPolicyId()).thenReturn(1L);
        when(groupRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(group));
        when(versionRepository.findByIdAndPolicyIdForDelete(15L, 1L)).thenReturn(Optional.of(mock(LeavePolicyVersion.class)));
        when(typeRuleRepository.findAllByPolicyVersionIdOrderByLeaveTypeIdAsc(15L)).thenReturn(List.of(rule));
        when(typeRuleRepository.findAllByPolicyVersionIdForUpdate(15L)).thenReturn(List.of(rule));
        when(typeRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(type));
        when(type.isActive()).thenReturn(true);
        when(type.getRequestUnit()).thenReturn(RequestUnit.DAY);
        when(type.getMaxMinutes()).thenReturn(120);
        when(groupRepository.updateActiveVersion(1L, 15L)).thenReturn(1);
    }

    @Test
    void locksGroupVersionOrderedMastersThenRulesBeforeUpdatingOnlyPointer() {
        LeavePolicyTypeRule otherRule = new LeavePolicyTypeRule(15L, 9L, true, PolicyAllowedUnits.FULL, null, null);
        when(typeRuleRepository.findAllByPolicyVersionIdOrderByLeaveTypeIdAsc(15L)).thenReturn(List.of(rule, otherRule));
        when(typeRuleRepository.findAllByPolicyVersionIdForUpdate(15L)).thenReturn(List.of(rule, otherRule));
        when(typeRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(type));
        // 최초 잠금 후 refresh에서는 아직 이전 포인터를 유지한다.
        doNothing().doAnswer(invocation -> {
            when(group.getActivePolicyVersionId()).thenReturn(15L);
            return null;
        }).when(entityManager).refresh(group);

        assertThat(service.activateVersion(1L, new LeavePolicyActivationRequest(15L)).activePolicyVersionId()).isEqualTo(15);

        InOrder order = inOrder(groupRepository, versionRepository, typeRepository, typeRuleRepository, accrualRepository, entityManager);
        order.verify(groupRepository).findByIdForUpdate(1L);
        order.verify(entityManager).refresh(group);
        order.verify(versionRepository).findByIdAndPolicyIdForDelete(15L, 1L);
        order.verify(typeRuleRepository).findAllByPolicyVersionIdOrderByLeaveTypeIdAsc(15L);
        order.verify(typeRepository).findByIdForUpdate(2L);
        order.verify(typeRepository).findByIdForUpdate(9L);
        order.verify(typeRuleRepository).findAllByPolicyVersionIdForUpdate(15L);
        order.verify(accrualRepository).findAllByPolicyVersionIdForUpdate(15L);
        order.verify(groupRepository).updateActiveVersion(1L, 15L);
        order.verify(entityManager).refresh(group);
        verify(versionRepository, never()).existsUsageHistory(anyLong());
        verify(groupRepository, never()).save(any());
    }

    @Test
    void repeatedSelectionValidatesMembershipButDoesNotReadRulesOrUpdatePointer() {
        when(group.getActivePolicyVersionId()).thenReturn(15L);
        service.activateVersion(1L, new LeavePolicyActivationRequest(15L));
        verify(versionRepository).findByIdAndPolicyIdForDelete(15L, 1L);
        verifyNoInteractions(typeRepository, typeRuleRepository, accrualRepository);
        verify(groupRepository, never()).updateActiveVersion(anyLong(), anyLong());
    }

    @Test
    void checksAdminBeforeAnyDatabaseWork() {
        when(employeeAccess.isAdmin(42L)).thenReturn(false);
        assertError(ErrorCode.ACCESS_DENIED);
        verifyNoInteractions(groupRepository, versionRepository, typeRepository, typeRuleRepository, accrualRepository, entityManager);
    }

    @Test
    void rejectsMissingGroupBeforeVersionLookup() {
        when(groupRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        assertError(ErrorCode.LEAVE_POLICY_GROUP_NOT_FOUND);
        verifyNoInteractions(versionRepository, typeRepository, typeRuleRepository, accrualRepository);
    }

    @Test
    void rejectsMissingOrForeignVersionBeforeRuleLookup() {
        when(versionRepository.findByIdAndPolicyIdForDelete(15L, 1L)).thenReturn(Optional.empty());
        assertError(ErrorCode.LEAVE_POLICY_VERSION_NOT_FOUND);
        verifyNoInteractions(typeRepository, typeRuleRepository, accrualRepository);
    }

    @Test
    void verifiesExactlyOneUpdatedRow() {
        when(groupRepository.updateActiveVersion(1L, 15L)).thenReturn(0);
        assertError(ErrorCode.LEAVE_POLICY_ACTIVATION_CONFLICT);
        verify(entityManager, times(1)).refresh(group);
    }

    static Stream<Arguments> invalidAccrualSettings() {
        return Stream.of(Arguments.of(-1, null, 480, AccrualFrequency.YEARLY),
                Arguments.of(12, 11, 480, AccrualFrequency.YEARLY),
                Arguments.of(0, null, 0, AccrualFrequency.MONTHLY),
                Arguments.of(0, null, -1, AccrualFrequency.ON_HIRE),
                Arguments.of(0, null, 480, null));
    }

    @ParameterizedTest
    @MethodSource("invalidAccrualSettings")
    void rejectsInvalidAccrualSettingsEvenIfRuleIsInactive(int min, Integer max, int grant, AccrualFrequency frequency) {
        LeaveAccrualRule accrualRule = new LeaveAccrualRule(mock(LeavePolicyVersion.class),
                new LeaveAccrualRuleSettings(min, max, frequency, grant, false));
        when(accrualRepository.findAllByPolicyVersionIdForUpdate(15L)).thenReturn(List.of(accrualRule));
        assertError(ErrorCode.LEAVE_POLICY_ACTIVATION_CONFLICT);
        verify(groupRepository, never()).updateActiveVersion(anyLong(), anyLong());
    }

    static Stream<Arguments> validAccrualSettings() {
        return Stream.of(AccrualFrequency.values())
                .flatMap(frequency -> Stream.of(Arguments.of(frequency, true), Arguments.of(frequency, false)));
    }

    @ParameterizedTest
    @MethodSource("validAccrualSettings")
    void acceptsEveryFrequencyAndBothRuleStates(AccrualFrequency frequency, boolean active) {
        LeaveAccrualRule accrualRule = new LeaveAccrualRule(mock(LeavePolicyVersion.class),
                new LeaveAccrualRuleSettings(0, 0, frequency, 1, active));
        when(accrualRepository.findAllByPolicyVersionIdForUpdate(15L)).thenReturn(List.of(accrualRule));
        service.activateVersion(1L, new LeavePolicyActivationRequest(15L));
        verify(groupRepository).updateActiveVersion(1L, 15L);
        assertThat(accrualRule.isActive()).isEqualTo(active);
    }

    static Stream<Arguments> invalidRuleLimits() {
        return Stream.of(Arguments.of(0, null), Arguments.of(-1, null), Arguments.of(null, 0),
                Arguments.of(null, -1), Arguments.of(90, 60));
    }

    @ParameterizedTest
    @MethodSource("invalidRuleLimits")
    void checksPositiveMinutesAndRangeBeforeUpdating(Integer min, Integer max) {
        LeavePolicyTypeRule invalidRule = new LeavePolicyTypeRule(15L, 2L, true, PolicyAllowedUnits.FULL, min, max);
        when(typeRuleRepository.findAllByPolicyVersionIdForUpdate(15L)).thenReturn(List.of(invalidRule));
        assertError(ErrorCode.LEAVE_POLICY_ACTIVATION_CONFLICT);
        verify(groupRepository, never()).updateActiveVersion(anyLong(), anyLong());
    }

    private void assertError(ErrorCode errorCode) {
        assertThatThrownBy(() -> service.activateVersion(1L, new LeavePolicyActivationRequest(15L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
