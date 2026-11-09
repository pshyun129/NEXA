package com.ssafy.leaveservice.domain.policy.dto;

import com.ssafy.leaveservice.domain.policy.entity.EmployeeLeavePolicyAssignment;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyGroup;

import java.time.OffsetDateTime;

public record EmployeeLeavePolicyAssignmentResponse(
        Long assignmentId,
        Long employeeId,
        Long policyId,
        String policyName,
        OffsetDateTime effectiveFrom,
        OffsetDateTime effectiveTo
) {

    public static EmployeeLeavePolicyAssignmentResponse from(EmployeeLeavePolicyAssignment assignment) {
        LeavePolicyGroup policyGroup = assignment.getPolicyGroup();
        return new EmployeeLeavePolicyAssignmentResponse(
                assignment.getAssignmentId(),
                assignment.getEmployeeId(),
                policyGroup.getPolicyId(),
                policyGroup.getName(),
                assignment.getEffectiveFrom(),
                assignment.getEffectiveTo()
        );
    }
}
