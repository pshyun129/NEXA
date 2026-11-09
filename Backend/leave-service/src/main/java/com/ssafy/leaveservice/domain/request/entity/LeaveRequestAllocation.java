package com.ssafy.leaveservice.domain.request.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.OffsetDateTime;

@Entity
@Table(name = "leave_request_allocation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LeaveRequestAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "allocation_id", nullable = false)
    private Long allocationId;

    @Column(name = "leave_req_id", nullable = false)
    private Long requestId;

    @Column(name = "request_date_id", nullable = false)
    private Long requestDateId;

    @Column(name = "emp_id", nullable = false)
    private Long employeeId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "grant_id", nullable = false)
    private Long grantId;

    @Column(name = "allocated_minutes", nullable = false)
    private int allocatedMinutes;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public static LeaveRequestAllocation reserve(
            Long requestId,
            Long requestDateId,
            Long employeeId,
            Long accountId,
            Long grantId,
            int minutes,
            OffsetDateTime asOf
    ) {
        LeaveRequestAllocation allocation = new LeaveRequestAllocation();
        allocation.requestId = requestId;
        allocation.requestDateId = requestDateId;
        allocation.employeeId = employeeId;
        allocation.accountId = accountId;
        allocation.grantId = grantId;
        allocation.allocatedMinutes = minutes;
        allocation.createdAt = asOf;
        return allocation;
    }
}
