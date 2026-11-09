package com.ssafy.leaveservice.domain.policy.repository;

import com.ssafy.leaveservice.domain.policy.entity.EmployeeLeavePolicyAssignment;
import com.ssafy.leaveservice.domain.policy.dto.MyLeavePolicyResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.time.OffsetDateTime;

public interface EmployeeLeavePolicyAssignmentRepository extends JpaRepository<EmployeeLeavePolicyAssignment, Long> {

    // 활성 버전 누락도 배정 행은 유지해 404와 409를 구분한다. 스칼라 조회로 한 SELECT의 스냅샷을 사용한다.
    @Query("""
            select new com.ssafy.leaveservice.domain.policy.dto.MyLeavePolicyResponse(
                assignment.assignmentId, policyGroup.policyId, policyGroup.name,
                assignment.effectiveFrom, assignment.effectiveTo,
                activeVersion.policyVersionId, activeVersion.versionNo, activeVersion.baseType,
                activeVersion.fiscalStart, activeVersion.unitMinutes, activeVersion.carryOverAllowed,
                activeVersion.carryOverLimitMinutes, activeVersion.expiryRule,
                activeVersion.effectiveFrom, activeVersion.effectiveTo
            )
            from EmployeeLeavePolicyAssignment assignment
            left join assignment.policyGroup policyGroup
            left join LeavePolicyVersion activeVersion
                on activeVersion.policyVersionId = policyGroup.activePolicyVersionId
                and activeVersion.policyGroup.policyId = policyGroup.policyId
            where assignment.employeeId = :employeeId
                and assignment.effectiveFrom <= :currentTime
                and (assignment.effectiveTo is null or assignment.effectiveTo > :currentTime)
            """)
    List<MyLeavePolicyResponse> findApplicablePolicies(
            @Param("employeeId") Long employeeId,
            @Param("currentTime") OffsetDateTime currentTime
    );

    // 단일 BIGINT advisory lock의 음수 영역을 직원 배정에 예약한다. 양수 직원 ID와 일대일 대응한다.
    @Query(value = "select 1 from pg_advisory_xact_lock(-cast(:employeeId as bigint))", nativeQuery = true)
    int lockEmployeeAssignments(@Param("employeeId") Long employeeId);

    @Query(value = """
            select * from employee_leave_policy_assignment
            where emp_id = :employeeId order by assignment_id for update
            """, nativeQuery = true)
    List<EmployeeLeavePolicyAssignment> findAllByEmployeeIdForUpdate(@Param("employeeId") Long employeeId);

    @Query("""
            select assignment from EmployeeLeavePolicyAssignment assignment
            join fetch assignment.policyGroup
            where assignment.employeeId = :employeeId
            order by assignment.effectiveFrom desc, assignment.assignmentId desc
            """)
    List<EmployeeLeavePolicyAssignment> findAllWithPolicyGroupByEmployeeId(@Param("employeeId") Long employeeId);
}
