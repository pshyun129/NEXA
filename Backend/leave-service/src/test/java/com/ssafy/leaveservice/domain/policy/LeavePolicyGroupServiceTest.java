package com.ssafy.leaveservice.domain.policy;

import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyGroupResponse;
import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyGroupCreateRequest;
import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyGroupUpdateRequest;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyGroup;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyGroupRepository;
import com.ssafy.leaveservice.domain.policy.service.LeavePolicyGroupService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LeavePolicyGroupServiceTest {

    private final LeavePolicyGroupRepository repository = mock(LeavePolicyGroupRepository.class);
    private final EmployeeAccess employeeAccess = mock(EmployeeAccess.class);
    private final LeavePolicyGroupService service = new LeavePolicyGroupService(repository, employeeAccess);

    @BeforeEach
    void setUp() {
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
    }

    @Test
    void mapsPolicyGroupIdsAndNames() throws Exception {
        LeavePolicyGroup firstGroup = createGroup(1L, "일반 직원 연차 정책");
        LeavePolicyGroup secondGroup = createGroup(2L, "특별 연차 정책");
        when(repository.findAllByOrderByPolicyIdAsc()).thenReturn(List.of(firstGroup, secondGroup));

        List<LeavePolicyGroupResponse> response = service.getAllPolicies();

        assertThat(response).containsExactly(
                new LeavePolicyGroupResponse(1L, "일반 직원 연차 정책"),
                new LeavePolicyGroupResponse(2L, "특별 연차 정책")
        );
    }

    @Test
    void returnsEmptyList() {
        when(repository.findAllByOrderByPolicyIdAsc()).thenReturn(List.of());

        assertThat(service.getAllPolicies()).isEmpty();
    }

    @Test
    void rejectsNonAdminBeforeQuery() {
        when(employeeAccess.isAdmin(42L)).thenReturn(false);

        assertThatThrownBy(service::getAllPolicies)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
        verifyNoInteractions(repository);
    }

    @Test
    void propagatesUnavailableAuthenticationBeforeQuery() {
        when(employeeAccess.currentEmployeeId()).thenThrow(new BusinessException(ErrorCode.AUTHENTICATION_UNAVAILABLE));

        assertThatThrownBy(service::getAllPolicies)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTHENTICATION_UNAVAILABLE));
        verifyNoInteractions(repository);
    }

    @Test
    void returnsRequestedPolicyGroup() throws Exception {
        LeavePolicyGroup policyGroup = createGroup(8L, "일반 직원 연차 정책");
        when(repository.findById(8L)).thenReturn(Optional.of(policyGroup));

        LeavePolicyGroupResponse response = service.getPolicyGroup(8L);

        assertThat(response).isEqualTo(new LeavePolicyGroupResponse(8L, "일반 직원 연차 정책"));
        verify(repository).findById(8L);
        verify(repository, never()).findAllByOrderByPolicyIdAsc();
    }

    @Test
    void throwsBusinessExceptionForMissingPolicyGroup() {
        when(repository.findById(9999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPolicyGroup(9999L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.LEAVE_POLICY_GROUP_NOT_FOUND));
    }

    @Test
    void rejectsNonAdminBeforeDetailQuery() {
        when(employeeAccess.isAdmin(42L)).thenReturn(false);

        assertThatThrownBy(() -> service.getPolicyGroup(1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
        verifyNoInteractions(repository);
    }

    @Test
    void returnsGeneratedIdAndNameAfterSaving() {
        LeavePolicyGroupCreateRequest request = new LeavePolicyGroupCreateRequest("일반 직원 연차 정책");
        when(repository.save(any(LeavePolicyGroup.class))).thenAnswer(invocation -> {
            LeavePolicyGroup savedPolicyGroup = invocation.getArgument(0);
            ReflectionTestUtils.setField(savedPolicyGroup, "policyId", 73L);
            return savedPolicyGroup;
        });

        LeavePolicyGroupResponse response = service.createPolicyGroup(request);

        assertThat(response).isEqualTo(new LeavePolicyGroupResponse(73L, "일반 직원 연차 정책"));
        verify(repository).save(any(LeavePolicyGroup.class));
    }

    @Test
    void rejectsNonAdminBeforeSavingGroup() {
        LeavePolicyGroupCreateRequest request = new LeavePolicyGroupCreateRequest("일반 직원 연차 정책");
        when(employeeAccess.isAdmin(42L)).thenReturn(false);

        assertThatThrownBy(() -> service.createPolicyGroup(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
        verifyNoInteractions(repository);
    }

    @Test
    void changesNameOnExistingEntityWithoutCallingSave() throws Exception {
        LeavePolicyGroup policyGroup = createGroup(8L, "일반 직원 연차 정책");
        LeavePolicyGroupUpdateRequest request = new LeavePolicyGroupUpdateRequest("정규직 연차 정책");
        when(repository.findById(8L)).thenReturn(Optional.of(policyGroup));

        LeavePolicyGroupResponse response = service.updatePolicyGroup(8L, request);

        assertThat(response).isEqualTo(new LeavePolicyGroupResponse(8L, "정규직 연차 정책"));
        assertThat(policyGroup.getName()).isEqualTo("정규직 연차 정책");
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsUpdatingMissingGroupWithExistingErrorCode() {
        LeavePolicyGroupUpdateRequest request = new LeavePolicyGroupUpdateRequest("정규직 연차 정책");
        when(repository.findById(9999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updatePolicyGroup(9999L, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.LEAVE_POLICY_GROUP_NOT_FOUND));
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsNonAdminBeforeUpdatingGroup() {
        LeavePolicyGroupUpdateRequest request = new LeavePolicyGroupUpdateRequest("정규직 연차 정책");
        when(employeeAccess.isAdmin(42L)).thenReturn(false);

        assertThatThrownBy(() -> service.updatePolicyGroup(1L, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
        verifyNoInteractions(repository);
    }

    private LeavePolicyGroup createGroup(Long policyId, String name) throws Exception {
        java.lang.reflect.Constructor<LeavePolicyGroup> constructor = LeavePolicyGroup.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        LeavePolicyGroup policyGroup = constructor.newInstance();
        ReflectionTestUtils.setField(policyGroup, "policyId", policyId);
        ReflectionTestUtils.setField(policyGroup, "name", name);
        return policyGroup;
    }
}
