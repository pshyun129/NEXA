package com.ssafy.user_service.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** 소속/직무 배정 (employee 테이블의 FK 컬럼 그룹). 모두 NOT NULL */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobAssignment {

    @Column(name = "dept_id", nullable = false)
    private Long deptId;                        // 부서번호

    @Column(name = "position_id", nullable = false)
    private Long positionId;                    // 직위번호

    @Column(name = "grade_id", nullable = false)
    private Long gradeId;                       // 직급번호

    @Column(name = "duty_id", nullable = false)
    private Long dutyId;                        // 직책번호

    @Column(name = "job_family_id", nullable = false)
    private Long jobFamilyId;                   // 직군번호

    @Column(name = "job_kind_id", nullable = false)
    private Long jobKindId;                     // 직종번호

    @Column(name = "job_role_id", nullable = false)
    private Long jobRoleId;                     // 직무번호

    @Column(name = "last_appoint_date")
    private LocalDate lastAppointDate;          // 최종발령일

    @Builder
    private JobAssignment(Long deptId, Long positionId, Long gradeId, Long dutyId,
                          Long jobFamilyId, Long jobKindId, Long jobRoleId, LocalDate lastAppointDate) {
        this.deptId = deptId;
        this.positionId = positionId;
        this.gradeId = gradeId;
        this.dutyId = dutyId;
        this.jobFamilyId = jobFamilyId;
        this.jobKindId = jobKindId;
        this.jobRoleId = jobRoleId;
        this.lastAppointDate = lastAppointDate;
    }
}
