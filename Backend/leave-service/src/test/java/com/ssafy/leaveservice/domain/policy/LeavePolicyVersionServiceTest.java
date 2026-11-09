package com.ssafy.leaveservice.domain.policy;

import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyVersionListResponse;
import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyVersionDetailResponse;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyGroup;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyVersion;
import com.ssafy.leaveservice.domain.policy.entity.PolicyBaseType;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyGroupRepository;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyVersionRepository;
import com.ssafy.leaveservice.domain.policy.service.LeavePolicyVersionService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import jakarta.validation.Validator;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LeavePolicyVersionServiceTest {

    private final LeavePolicyGroupRepository leavePolicyGroupRepository = mock(LeavePolicyGroupRepository.class);
    private final LeavePolicyVersionRepository leavePolicyVersionRepository = mock(LeavePolicyVersionRepository.class);
    private final EmployeeAccess employeeAccess = mock(EmployeeAccess.class);
    private final LeavePolicyVersionService service = new LeavePolicyVersionService(
            leavePolicyGroupRepository, leavePolicyVersionRepository, employeeAccess, mock(Validator.class)
    );
    private final LeavePolicyGroup policyGroup = new LeavePolicyGroup("일반 직원 연차 정책");

    @BeforeEach
    void setUp() {
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        when(leavePolicyGroupRepository.findById(1L)).thenReturn(Optional.of(policyGroup));
    }

    @Test
    void marksOnlyPointerTargetAsActive() {
        ReflectionTestUtils.setField(policyGroup, "activePolicyVersionId", 11L);
        LeavePolicyVersion firstVersion = createVersion(11L, 1);
        LeavePolicyVersion secondVersion = createVersion(12L, 2);
        when(leavePolicyVersionRepository.findAllByPolicyGroupPolicyIdOrderByVersionNoAsc(1L))
                .thenReturn(List.of(firstVersion, secondVersion));

        List<LeavePolicyVersionListResponse> response = service.getPolicyVersions(1L);

        assertThat(response).containsExactly(
                new LeavePolicyVersionListResponse(11L, 1, PolicyBaseType.HIRE_DATE, null, null, true),
                new LeavePolicyVersionListResponse(12L, 2, PolicyBaseType.HIRE_DATE, null, null, false)
        );
    }

    @Test
    void marksAllVersionsInactiveWhenPointerIsNull() {
        LeavePolicyVersion firstVersion = createVersion(11L, 1);
        LeavePolicyVersion secondVersion = createVersion(12L, 2);
        when(leavePolicyVersionRepository.findAllByPolicyGroupPolicyIdOrderByVersionNoAsc(1L))
                .thenReturn(List.of(firstVersion, secondVersion));

        List<LeavePolicyVersionListResponse> response = service.getPolicyVersions(1L);

        assertThat(response).extracting(LeavePolicyVersionListResponse::isActive).containsExactly(false, false);
    }

    @Test
    void returnsEmptyListForGroupWithoutVersions() {
        when(leavePolicyVersionRepository.findAllByPolicyGroupPolicyIdOrderByVersionNoAsc(1L)).thenReturn(List.of());

        assertThat(service.getPolicyVersions(1L)).isEmpty();
    }

    @Test
    void rejectsMissingGroupBeforeVersionQuery() {
        when(leavePolicyGroupRepository.findById(9999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPolicyVersions(9999L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.LEAVE_POLICY_GROUP_NOT_FOUND));
        verifyNoInteractions(leavePolicyVersionRepository);
    }

    @Test
    void rejectsNonAdminBeforeDatabaseQueries() {
        when(employeeAccess.isAdmin(42L)).thenReturn(false);

        assertThatThrownBy(() -> service.getPolicyVersions(1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
        verifyNoInteractions(leavePolicyGroupRepository, leavePolicyVersionRepository);
    }

    @Test
    void propagatesUnavailableAuthenticationBeforeDatabaseQueries() {
        when(employeeAccess.currentEmployeeId()).thenThrow(new BusinessException(ErrorCode.AUTHENTICATION_UNAVAILABLE));

        assertThatThrownBy(() -> service.getPolicyVersions(1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTHENTICATION_UNAVAILABLE));
        verifyNoInteractions(leavePolicyGroupRepository, leavePolicyVersionRepository);
    }

    private LeavePolicyVersion createVersion(Long policyVersionId, int versionNo) {
        LeavePolicyVersion policyVersion = mock(LeavePolicyVersion.class);
        when(policyVersion.getPolicyVersionId()).thenReturn(policyVersionId);
        when(policyVersion.getVersionNo()).thenReturn(versionNo);
        when(policyVersion.getBaseType()).thenReturn(PolicyBaseType.HIRE_DATE);
        return policyVersion;
    }

    @Test
    void returnsDetailForVersionWithinRequestedGroup() {
        ReflectionTestUtils.setField(policyGroup, "policyId", 1L);
        ReflectionTestUtils.setField(policyGroup, "activePolicyVersionId", 11L);
        LeavePolicyVersion policyVersion = createVersion(11L, 1);
        when(leavePolicyVersionRepository.findByPolicyVersionIdAndPolicyGroupPolicyId(11L, 1L))
                .thenReturn(Optional.of(policyVersion));

        LeavePolicyVersionDetailResponse response = service.getPolicyVersion(1L, 11L);

        assertThat(response.policyId()).isEqualTo(1L);
        assertThat(response.policyVersionId()).isEqualTo(11L);
        assertThat(response.isActive()).isTrue();
        verify(leavePolicyVersionRepository).findByPolicyVersionIdAndPolicyGroupPolicyId(11L, 1L);
        verify(leavePolicyVersionRepository, never()).findById(anyLong());
    }

    @ParameterizedTest
    @ValueSource(longs = {9999L, 21L})
    void rejectsMissingOrOtherGroupVersionWithSameError(Long versionId) {
        when(leavePolicyVersionRepository.findByPolicyVersionIdAndPolicyGroupPolicyId(versionId, 1L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPolicyVersion(1L, versionId))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.LEAVE_POLICY_VERSION_NOT_FOUND));
    }

    @Test
    void rejectsMissingDetailGroupBeforeVersionQuery() {
        assertThatThrownBy(() -> service.getPolicyVersion(9999L, 11L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.LEAVE_POLICY_GROUP_NOT_FOUND));
        verifyNoInteractions(leavePolicyVersionRepository);
    }

    @Test
    void rejectsNonAdminDetailBeforeDatabaseQueries() {
        when(employeeAccess.isAdmin(42L)).thenReturn(false);

        assertThatThrownBy(() -> service.getPolicyVersion(1L, 11L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
        verifyNoInteractions(leavePolicyGroupRepository, leavePolicyVersionRepository);
    }
}
