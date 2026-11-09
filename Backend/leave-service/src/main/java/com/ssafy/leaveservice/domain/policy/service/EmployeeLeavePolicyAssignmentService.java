package com.ssafy.leaveservice.domain.policy.service;

import com.ssafy.leaveservice.domain.policy.dto.EmployeeLeavePolicyAssignmentResponse;
import com.ssafy.leaveservice.domain.policy.dto.EmployeeLeavePolicyAssignmentCreateRequest;
import com.ssafy.leaveservice.domain.policy.entity.EmployeeLeavePolicyAssignment;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyGroup;
import com.ssafy.leaveservice.domain.policy.repository.EmployeeLeavePolicyAssignmentRepository;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyGroupRepository;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

@com.ssafy.leaveservice.global.auth.LeaveAdministratorOnly
@Service
@RequiredArgsConstructor
public class EmployeeLeavePolicyAssignmentService {

    private final EmployeeLeavePolicyAssignmentRepository employeeLeavePolicyAssignmentRepository;
    private final EmployeeAccess employeeAccess;
    private final LeavePolicyGroupRepository leavePolicyGroupRepository;
    private final Validator validator;
    private final EntityManager entityManager;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EmployeeLeavePolicyAssignmentResponse createPolicyAssignment(
            Long employeeId,
            EmployeeLeavePolicyAssignmentCreateRequest request
    ) {
        // 관리자 권한 확인
        Long adminEmployeeId = employeeAccess.currentEmployeeId();
        if (!employeeAccess.isAdmin(adminEmployeeId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        // 입력값과 DB 정밀도에 맞춘 최종 기간 검증
        if (employeeId == null || employeeId <= 0 || request == null || !validator.validate(request).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        OffsetDateTime serverNow = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        OffsetDateTime effectiveFrom = request.effectiveFrom() == null
                ? serverNow : request.effectiveFrom().truncatedTo(ChronoUnit.MICROS);
        OffsetDateTime effectiveTo = request.effectiveTo() == null
                ? null : request.effectiveTo().truncatedTo(ChronoUnit.MICROS);
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        // TODO: HR Service 계약 확정 후 대상 직원의 존재·재직 상태를 검증한다. 현재는 확인하지 못한다.

        // 직원별 잠금 후 대상 그룹과 최신 배정 이력을 순서대로 잠금
        employeeLeavePolicyAssignmentRepository.lockEmployeeAssignments(employeeId);
        LeavePolicyGroup policyGroup = leavePolicyGroupRepository.findByIdForAssignment(request.policyId()).orElseThrow(
                () -> new BusinessException(ErrorCode.LEAVE_POLICY_GROUP_NOT_FOUND)
        );
        entityManager.refresh(policyGroup);
        if (policyGroup.getActivePolicyVersionId() == null) {
            throw new BusinessException(ErrorCode.LEAVE_POLICY_ASSIGNMENT_CONFLICT);
        }
        List<EmployeeLeavePolicyAssignment> assignments = employeeLeavePolicyAssignmentRepository
                .findAllByEmployeeIdForUpdate(employeeId);
        for (EmployeeLeavePolicyAssignment assignment : assignments) {
            entityManager.refresh(assignment);
        }

        // 전체 기간을 확인한 후 자동 종료 가능한 배정 하나만 결정
        EmployeeLeavePolicyAssignment assignmentToClose = findAssignmentToClose(
                assignments, policyGroup.getPolicyId(), effectiveFrom, effectiveTo, serverNow
        );

        // 기존 배정 종료와 신규 배정을 같은 트랜잭션에서 저장
        if (assignmentToClose != null) {
            assignmentToClose.closeAt(effectiveFrom);
            employeeLeavePolicyAssignmentRepository.flush();
        }
        EmployeeLeavePolicyAssignment newAssignment = new EmployeeLeavePolicyAssignment(
                employeeId, policyGroup, effectiveFrom, effectiveTo
        );
        EmployeeLeavePolicyAssignment savedAssignment = employeeLeavePolicyAssignmentRepository.saveAndFlush(newAssignment);

        // DB에서 생성된 ID와 신규 배정 정보 반환
        EmployeeLeavePolicyAssignmentResponse response = EmployeeLeavePolicyAssignmentResponse.from(savedAssignment);
        return response;
    }

    private EmployeeLeavePolicyAssignment findAssignmentToClose(
            List<EmployeeLeavePolicyAssignment> assignments,
            Long policyId,
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo,
            OffsetDateTime serverNow
    ) {
        EmployeeLeavePolicyAssignment assignmentToClose = null;
        for (EmployeeLeavePolicyAssignment assignment : assignments) {
            OffsetDateTime existingFrom = assignment.getEffectiveFrom();
            OffsetDateTime existingTo = assignment.getEffectiveTo();
            if (existingFrom.isEqual(effectiveFrom)) {
                throw new BusinessException(ErrorCode.LEAVE_POLICY_ASSIGNMENT_CONFLICT);
            }

            boolean periodsOverlap = (effectiveTo == null || existingFrom.isBefore(effectiveTo))
                    && (existingTo == null || effectiveFrom.isBefore(existingTo));
            if (!periodsOverlap) {
                continue;
            }

            boolean canClose = existingTo == null && existingFrom.isBefore(effectiveFrom)
                    && !effectiveFrom.isBefore(serverNow)
                    && !Objects.equals(assignment.getPolicyGroup().getPolicyId(), policyId);
            if (!canClose || assignmentToClose != null) {
                throw new BusinessException(ErrorCode.LEAVE_POLICY_ASSIGNMENT_CONFLICT);
            }
            assignmentToClose = assignment;
        }
        return assignmentToClose;
    }

    @Transactional(readOnly = true)
    public List<EmployeeLeavePolicyAssignmentResponse> getPolicyAssignments(Long employeeId) {
        // 관리자 권한 확인
        Long adminEmployeeId = employeeAccess.currentEmployeeId();
        if (!employeeAccess.isAdmin(adminEmployeeId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        // 조회 대상 직원 ID 검증
        if (employeeId == null || employeeId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        // 기간으로 제외하지 않고 전체 배정과 현재 정책 그룹 이름 조회
        List<EmployeeLeavePolicyAssignment> assignments = employeeLeavePolicyAssignmentRepository
                .findAllWithPolicyGroupByEmployeeId(employeeId);

        // 배정 이력만 응답으로 변환
        List<EmployeeLeavePolicyAssignmentResponse> response = assignments.stream()
                .map(EmployeeLeavePolicyAssignmentResponse::from)
                .toList();
        return response;
    }
}
