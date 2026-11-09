package com.ssafy.leaveservice.domain.policy;

import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyVersionUpdateRequest;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyGroup;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyVersion;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyVersionSettings;
import com.ssafy.leaveservice.domain.policy.entity.PolicyBaseType;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyGroupRepository;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyVersionRepository;
import com.ssafy.leaveservice.domain.policy.service.LeavePolicyVersionService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LeavePolicyVersionPatchServiceTest {

    private final LeavePolicyGroupRepository groupRepository = mock(LeavePolicyGroupRepository.class);
    private final LeavePolicyVersionRepository versionRepository = mock(LeavePolicyVersionRepository.class);
    private final EmployeeAccess employeeAccess = mock(EmployeeAccess.class);
    private final LeavePolicyGroup policyGroup = new LeavePolicyGroup("일반 정책");
    private final LeavePolicyVersion policyVersion = spy(LeavePolicyVersion.builder()
            .policyGroup(policyGroup)
            .versionNo(1)
            .baseType(PolicyBaseType.HIRE_DATE)
            .unitMinutes(30)
            .carryOverAllowed(false)
            .expiryRule("END_OF_VALIDITY")
            .effectiveFrom(OffsetDateTime.parse("2026-01-01T00:00:00Z"))
            .build());
    private ValidatorFactory validatorFactory;
    private LeavePolicyVersionService service;

    @BeforeEach
    void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        service = new LeavePolicyVersionService(groupRepository, versionRepository, employeeAccess, validatorFactory.getValidator());
        ReflectionTestUtils.setField(policyGroup, "policyId", 1L);
        ReflectionTestUtils.setField(policyVersion, "policyVersionId", 11L);
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        when(groupRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(policyGroup));
        when(versionRepository.findByIdAndPolicyIdForUpdate(11L, 1L)).thenReturn(Optional.of(policyVersion));
    }

    @AfterEach
    void tearDown() {
        validatorFactory.close();
    }

    @Test
    void locksGroupThenVersionAndUsesDirtyChecking() {
        LeavePolicyVersionUpdateRequest request = unitUpdate(17);

        assertThat(service.updatePolicyVersion(1L, 11L, request).unitMinutes()).isEqualTo(17);

        InOrder order = inOrder(groupRepository, versionRepository);
        order.verify(groupRepository).findByIdForUpdate(1L);
        order.verify(versionRepository).findByIdAndPolicyIdForUpdate(11L, 1L);
        order.verify(versionRepository).existsUsageHistory(11L);
        verify(policyVersion).updateSettings(any(LeavePolicyVersionSettings.class));
        verify(versionRepository, never()).save(any());
    }

    @Test
    void validatesMergedSettingsBeforeCheckingUsageOrMutatingEntity() {
        LeavePolicyVersionUpdateRequest request = new LeavePolicyVersionUpdateRequest(
                PolicyBaseType.FISCAL_YEAR, null, 17, null, null, null, null, null,
                Set.of("baseType", "unitMinutes")
        );

        assertError(() -> service.updatePolicyVersion(1L, 11L, request), ErrorCode.INVALID_INPUT);

        assertThat(policyVersion.getUnitMinutes()).isEqualTo(30);
        verify(versionRepository, never()).existsUsageHistory(anyLong());
        verify(policyVersion, never()).updateSettings(any());
    }

    @Test
    void skipsMutationAndUsageQueriesForSameInstantOnActiveVersion() {
        ReflectionTestUtils.setField(policyGroup, "activePolicyVersionId", 11L);
        LeavePolicyVersionUpdateRequest request = new LeavePolicyVersionUpdateRequest(
                null, null, null, null, null, null, OffsetDateTime.parse("2026-01-01T09:00:00+09:00"), null,
                Set.of("effectiveFrom")
        );

        assertThat(service.updatePolicyVersion(1L, 11L, request).isActive()).isTrue();

        verify(versionRepository, never()).existsUsageHistory(anyLong());
        verify(policyVersion, never()).updateSettings(any());
    }

    @Test
    void leavesEntityUntouchedWhenUsagePreventsChange() {
        when(versionRepository.existsUsageHistory(11L)).thenReturn(true);

        assertError(() -> service.updatePolicyVersion(1L, 11L, unitUpdate(17)), ErrorCode.LEAVE_POLICY_VERSION_IN_USE);

        assertThat(policyVersion.getUnitMinutes()).isEqualTo(30);
        verify(policyVersion, never()).updateSettings(any());
    }

    @Test
    void rejectsExplicitNullWithoutDefaultingExpiryRule() {
        LeavePolicyVersionUpdateRequest request = new LeavePolicyVersionUpdateRequest(
                null, null, null, null, null, null, null, null, Set.of("expiryRule")
        );

        assertError(() -> service.updatePolicyVersion(1L, 11L, request), ErrorCode.INVALID_INPUT);

        verify(policyVersion, never()).updateSettings(any());
    }

    @Test
    void rejectsNonAdminBeforeTakingLocks() {
        when(employeeAccess.isAdmin(42L)).thenReturn(false);

        assertError(() -> service.updatePolicyVersion(1L, 11L, unitUpdate(17)), ErrorCode.ACCESS_DENIED);

        verifyNoInteractions(groupRepository, versionRepository);
    }

    @Test
    void rejectsMissingGroupBeforeVersionLock() {
        when(groupRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        assertError(() -> service.updatePolicyVersion(1L, 11L, unitUpdate(17)), ErrorCode.LEAVE_POLICY_GROUP_NOT_FOUND);

        verifyNoInteractions(versionRepository);
    }

    @Test
    void rejectsMissingOrOtherGroupVersionWithVersionError() {
        when(versionRepository.findByIdAndPolicyIdForUpdate(11L, 1L)).thenReturn(Optional.empty());

        assertError(() -> service.updatePolicyVersion(1L, 11L, unitUpdate(17)), ErrorCode.LEAVE_POLICY_VERSION_NOT_FOUND);

        verify(versionRepository, never()).existsUsageHistory(anyLong());
    }

    private LeavePolicyVersionUpdateRequest unitUpdate(int unitMinutes) {
        return new LeavePolicyVersionUpdateRequest(
                null, null, unitMinutes, null, null, null, null, null, Set.of("unitMinutes")
        );
    }

    private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation, ErrorCode errorCode) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
