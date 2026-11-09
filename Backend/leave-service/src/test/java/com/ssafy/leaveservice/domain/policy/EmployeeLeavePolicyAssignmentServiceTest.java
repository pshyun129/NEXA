package com.ssafy.leaveservice.domain.policy;

import com.ssafy.leaveservice.domain.policy.dto.EmployeeLeavePolicyAssignmentResponse;
import com.ssafy.leaveservice.domain.policy.entity.EmployeeLeavePolicyAssignment;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyGroup;
import com.ssafy.leaveservice.domain.policy.repository.EmployeeLeavePolicyAssignmentRepository;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyGroupRepository;
import com.ssafy.leaveservice.domain.policy.service.EmployeeLeavePolicyAssignmentService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmployeeLeavePolicyAssignmentServiceTest {

    private final EmployeeLeavePolicyAssignmentRepository repository = mock(EmployeeLeavePolicyAssignmentRepository.class);
    private final EmployeeAccess employeeAccess = mock(EmployeeAccess.class);
    private final EmployeeLeavePolicyAssignmentService service = new EmployeeLeavePolicyAssignmentService(
            repository, employeeAccess,
            mock(LeavePolicyGroupRepository.class), mock(Validator.class), mock(EntityManager.class)
    );

    @BeforeEach
    void setUp() {
        when(employeeAccess.currentEmployeeId()).thenReturn(7L);
        when(employeeAccess.isAdmin(7L)).thenReturn(true);
    }

    @Test
    void queriesTargetEmployeeAndMapsCurrentGroupNameAndPeriod() {
        LeavePolicyGroup group = mock(LeavePolicyGroup.class);
        when(group.getPolicyId()).thenReturn(3L);
        when(group.getName()).thenReturn("현재 정책 이름");
        EmployeeLeavePolicyAssignment assignment = mock(EmployeeLeavePolicyAssignment.class);
        OffsetDateTime effectiveFrom = OffsetDateTime.parse("2020-01-01T09:00:00+09:00");
        when(assignment.getAssignmentId()).thenReturn(9L);
        when(assignment.getEmployeeId()).thenReturn(42L);
        when(assignment.getPolicyGroup()).thenReturn(group);
        when(assignment.getEffectiveFrom()).thenReturn(effectiveFrom);
        when(repository.findAllWithPolicyGroupByEmployeeId(42L)).thenReturn(List.of(assignment));

        List<EmployeeLeavePolicyAssignmentResponse> response = service.getPolicyAssignments(42L);

        assertThat(response).containsExactly(new EmployeeLeavePolicyAssignmentResponse(
                9L, 42L, 3L, "현재 정책 이름", effectiveFrom, null
        ));
        verify(repository).findAllWithPolicyGroupByEmployeeId(42L);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void returnsEmptyListWithoutInferringThatEmployeeDoesNotExist() {
        when(repository.findAllWithPolicyGroupByEmployeeId(999L)).thenReturn(List.of());
        assertThat(service.getPolicyAssignments(999L)).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void rejectsInvalidEmployeeIdBeforeQuery(Long employeeId) {
        assertError(employeeId, ErrorCode.INVALID_INPUT);
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsNonAdminBeforeCheckingTargetIdOrReadingHistory() {
        when(employeeAccess.isAdmin(7L)).thenReturn(false);
        assertError(0L, ErrorCode.ACCESS_DENIED);
        verifyNoInteractions(repository);
    }

    @Test
    void propagatesUnavailableAuthenticationWithoutReadingHistory() {
        when(employeeAccess.currentEmployeeId()).thenThrow(new BusinessException(ErrorCode.AUTHENTICATION_UNAVAILABLE));
        assertError(42L, ErrorCode.AUTHENTICATION_UNAVAILABLE);
        verifyNoInteractions(repository);
    }

    private void assertError(Long employeeId, ErrorCode errorCode) {
        assertThatThrownBy(() -> service.getPolicyAssignments(employeeId))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
