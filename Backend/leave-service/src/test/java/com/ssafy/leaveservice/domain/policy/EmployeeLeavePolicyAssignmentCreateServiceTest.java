package com.ssafy.leaveservice.domain.policy;

import com.ssafy.leaveservice.domain.policy.dto.EmployeeLeavePolicyAssignmentCreateRequest;
import com.ssafy.leaveservice.domain.policy.entity.EmployeeLeavePolicyAssignment;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyGroup;
import com.ssafy.leaveservice.domain.policy.repository.EmployeeLeavePolicyAssignmentRepository;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyGroupRepository;
import com.ssafy.leaveservice.domain.policy.service.EmployeeLeavePolicyAssignmentService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmployeeLeavePolicyAssignmentCreateServiceTest {

    private final EmployeeLeavePolicyAssignmentRepository repository = mock(EmployeeLeavePolicyAssignmentRepository.class);
    private final LeavePolicyGroupRepository groupRepository = mock(LeavePolicyGroupRepository.class);
    private final EmployeeAccess employeeAccess = mock(EmployeeAccess.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final EmployeeLeavePolicyAssignmentService service = new EmployeeLeavePolicyAssignmentService(
            repository, employeeAccess, groupRepository, mock(Validator.class), entityManager
    );
    private final LeavePolicyGroup previousGroup = group(1L);
    private final LeavePolicyGroup targetGroup = group(2L);
    private final OffsetDateTime start = OffsetDateTime.parse("3000-01-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        when(employeeAccess.currentEmployeeId()).thenReturn(7L);
        when(employeeAccess.isAdmin(7L)).thenReturn(true);
        when(groupRepository.findByIdForAssignment(2L)).thenReturn(Optional.of(targetGroup));
        when(repository.saveAndFlush(any(EmployeeLeavePolicyAssignment.class))).thenAnswer(invocation -> {
            EmployeeLeavePolicyAssignment saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "assignmentId", 99L);
            return saved;
        });
    }

    @Test
    void locksEmployeeThenGroupThenAssignmentsAndClosesBeforeInsert() {
        EmployeeLeavePolicyAssignment existing = spy(new EmployeeLeavePolicyAssignment(
                42L, previousGroup, OffsetDateTime.parse("2020-01-01T00:00:00Z"), null
        ));
        ReflectionTestUtils.setField(existing, "assignmentId", 5L);
        when(repository.findAllByEmployeeIdForUpdate(42L)).thenReturn(List.of(existing));

        assertThat(service.createPolicyAssignment(42L, request()).assignmentId()).isEqualTo(99);

        InOrder order = inOrder(repository, groupRepository, entityManager, existing);
        order.verify(repository).lockEmployeeAssignments(42L);
        order.verify(groupRepository).findByIdForAssignment(2L);
        order.verify(entityManager).refresh(targetGroup);
        order.verify(repository).findAllByEmployeeIdForUpdate(42L);
        order.verify(entityManager).refresh(existing);
        order.verify(existing).closeAt(start);
        order.verify(repository).flush();
        order.verify(repository).saveAndFlush(any(EmployeeLeavePolicyAssignment.class));
        assertThat(existing.getAssignmentId()).isEqualTo(5);
        assertThat(existing.getPolicyGroup().getPolicyId()).isEqualTo(1);
        assertThat(existing.getEffectiveTo()).isEqualTo(start);
    }

    @Test
    void validatesAllPeriodsBeforeMutatingAnOtherwiseClosableAssignment() {
        EmployeeLeavePolicyAssignment open = new EmployeeLeavePolicyAssignment(42L, previousGroup, start.minusYears(1), null);
        EmployeeLeavePolicyAssignment finite = new EmployeeLeavePolicyAssignment(42L, previousGroup, start.plusDays(1), start.plusDays(2));
        when(repository.findAllByEmployeeIdForUpdate(42L)).thenReturn(List.of(open, finite));
        assertError(ErrorCode.LEAVE_POLICY_ASSIGNMENT_CONFLICT);
        assertThat(open.getEffectiveTo()).isNull();
        verify(repository, never()).flush();
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void refusesToChooseArbitrarilyBetweenTwoOverlappingOpenAssignments() {
        EmployeeLeavePolicyAssignment first = new EmployeeLeavePolicyAssignment(42L, previousGroup, start.minusYears(2), null);
        EmployeeLeavePolicyAssignment second = new EmployeeLeavePolicyAssignment(42L, previousGroup, start.minusYears(1), null);
        when(repository.findAllByEmployeeIdForUpdate(42L)).thenReturn(List.of(first, second));
        assertError(ErrorCode.LEAVE_POLICY_ASSIGNMENT_CONFLICT);
        assertThat(first.getEffectiveTo()).isNull();
        assertThat(second.getEffectiveTo()).isNull();
    }

    @Test
    void missingGroupStopsBeforeReadingHistory() {
        when(groupRepository.findByIdForAssignment(2L)).thenReturn(Optional.empty());
        assertError(ErrorCode.LEAVE_POLICY_GROUP_NOT_FOUND);
        verify(repository, never()).findAllByEmployeeIdForUpdate(anyLong());
    }

    @Test
    void inactiveGroupStopsBeforeReadingHistory() {
        ReflectionTestUtils.setField(targetGroup, "activePolicyVersionId", null);
        assertError(ErrorCode.LEAVE_POLICY_ASSIGNMENT_CONFLICT);
        verify(repository, never()).findAllByEmployeeIdForUpdate(anyLong());
    }

    @Test
    void deniedAdminDoesNotAcquireAnyLock() {
        when(employeeAccess.isAdmin(7L)).thenReturn(false);
        assertError(ErrorCode.ACCESS_DENIED);
        verifyNoInteractions(repository, groupRepository, entityManager);
    }

    @Test
    void invalidEmployeeDoesNotAcquireAnyLock() {
        assertThatThrownBy(() -> service.createPolicyAssignment(0L, request()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        verifyNoInteractions(repository, groupRepository, entityManager);
    }

    private EmployeeLeavePolicyAssignmentCreateRequest request() {
        return new EmployeeLeavePolicyAssignmentCreateRequest(2L, start, null);
    }

    private void assertError(ErrorCode code) {
        assertThatThrownBy(() -> service.createPolicyAssignment(42L, request()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    private LeavePolicyGroup group(Long id) {
        LeavePolicyGroup group = new LeavePolicyGroup("정책 " + id);
        ReflectionTestUtils.setField(group, "policyId", id);
        ReflectionTestUtils.setField(group, "activePolicyVersionId", id * 10);
        return group;
    }
}
