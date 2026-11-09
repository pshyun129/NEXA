package com.ssafy.leaveservice.domain.policy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

import java.time.OffsetDateTime;

@Entity
@DynamicUpdate
@Table(name = "employee_leave_policy_assignment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmployeeLeavePolicyAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "assignment_id")
    private Long assignmentId;

    @Column(name = "emp_id", nullable = false)
    private Long employeeId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policy_id", nullable = false)
    private LeavePolicyGroup policyGroup;

    @Column(name = "effective_from", nullable = false)
    private OffsetDateTime effectiveFrom;

    @Column(name = "effective_to")
    private OffsetDateTime effectiveTo;

    public EmployeeLeavePolicyAssignment(
            Long employeeId,
            LeavePolicyGroup policyGroup,
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo
    ) {
        this.employeeId = employeeId;
        this.policyGroup = policyGroup;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
    }

    public void closeAt(OffsetDateTime effectiveTo) {
        this.effectiveTo = effectiveTo;
    }
}
