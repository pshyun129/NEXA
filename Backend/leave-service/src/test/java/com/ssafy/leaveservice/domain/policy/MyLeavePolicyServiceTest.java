package com.ssafy.leaveservice.domain.policy;

import com.ssafy.leaveservice.domain.policy.dto.MyLeavePolicyResponse;
import com.ssafy.leaveservice.domain.policy.entity.PolicyBaseType;
import com.ssafy.leaveservice.domain.policy.repository.EmployeeLeavePolicyAssignmentRepository;
import com.ssafy.leaveservice.domain.policy.service.MyLeavePolicyService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MyLeavePolicyServiceTest {

    private final EmployeeLeavePolicyAssignmentRepository repository = mock(EmployeeLeavePolicyAssignmentRepository.class);
    private final EmployeeAccess employeeAccess = mock(EmployeeAccess.class);
    private final MyLeavePolicyService service = new MyLeavePolicyService(repository, employeeAccess);

    @BeforeEach
    void setUp() {
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
    }

    @Test
    void usesAuthenticatedEmployeeAndOneTimestampWithoutRequiringAdmin() {
        MyLeavePolicyResponse policy = policy(1L, 15L);
        when(repository.findApplicablePolicies(eq(42L), any())).thenReturn(List.of(policy));
        OffsetDateTime before = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);

        assertThat(service.getMyPolicy()).isSameAs(policy);

        ArgumentCaptor<OffsetDateTime> currentTime = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repository).findApplicablePolicies(eq(42L), currentTime.capture());
        assertThat(currentTime.getValue().toInstant()).isBetween(before.toInstant(), OffsetDateTime.now().toInstant());
        assertThat(currentTime.getValue().getNano() % 1000).isZero();
        verify(employeeAccess, never()).isAdmin(anyLong());
        verifyNoMoreInteractions(repository);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void rejectsMissingOrInvalidIdentityBeforeDatabaseAccess(Long employeeId) {
        when(employeeAccess.currentEmployeeId()).thenReturn(employeeId);
        assertError(ErrorCode.UNAUTHORIZED);
        verifyNoInteractions(repository);
    }

    @Test
    void propagatesUnauthenticatedError() {
        when(employeeAccess.currentEmployeeId()).thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));
        assertError(ErrorCode.UNAUTHORIZED);
        verifyNoInteractions(repository);
    }

    @Test
    void preservesUnavailableProductionAuthenticationError() {
        when(employeeAccess.currentEmployeeId()).thenThrow(new BusinessException(ErrorCode.AUTHENTICATION_UNAVAILABLE));
        assertError(ErrorCode.AUTHENTICATION_UNAVAILABLE);
        verifyNoInteractions(repository);
    }

    @Test
    void missingCurrentAssignmentIsDistinctFromAdminGroupLookupFailure() {
        when(repository.findApplicablePolicies(eq(42L), any())).thenReturn(List.of());
        assertError(ErrorCode.CURRENT_LEAVE_POLICY_NOT_FOUND);
    }

    @Test
    void rejectsMultipleAssignmentsEvenIfOneHasNoActiveVersion() {
        when(repository.findApplicablePolicies(eq(42L), any())).thenReturn(List.of(policy(1L, null), policy(2L, 21L)));
        assertError(ErrorCode.CURRENT_LEAVE_POLICY_CONFLICT);
    }

    @Test
    void rejectsMissingOrForeignActiveVersion() {
        when(repository.findApplicablePolicies(eq(42L), any())).thenReturn(List.of(policy(1L, null)));
        assertError(ErrorCode.CURRENT_LEAVE_POLICY_CONFLICT);
    }

    @Test
    void rejectsMissingGroupInsteadOfHidingAssignment() {
        when(repository.findApplicablePolicies(eq(42L), any())).thenReturn(List.of(policy(null, null)));
        assertError(ErrorCode.CURRENT_LEAVE_POLICY_CONFLICT);
    }

    private void assertError(ErrorCode errorCode) {
        assertThatThrownBy(service::getMyPolicy).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
        verify(employeeAccess, never()).isAdmin(anyLong());
    }

    private MyLeavePolicyResponse policy(Long policyId, Long versionId) {
        return new MyLeavePolicyResponse(31L, policyId, "일반 정책", OffsetDateTime.parse("2020-01-01T00:00:00Z"),
                null, versionId, 2, PolicyBaseType.FISCAL_YEAR, "01-01", 60, true, 240, "END_OF_VALIDITY", null, null);
    }
}
