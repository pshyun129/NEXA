package com.ssafy.user_service.domain.user.entity;

import com.ssafy.user_service.domain.user.entity.enums.EmpType;
import com.ssafy.user_service.domain.user.entity.enums.EmploymentStatus;
import com.ssafy.user_service.domain.user.entity.enums.HireType;
import com.ssafy.user_service.global.security.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Table(name = "employee")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "emp_id")
    private Long empId;                         // 사원ID

    @Column(name = "emp_no")
    private String empNo;                       // 사원번호

    @Column(name = "login_id", length = 255)
    private String loginId;                     // 이메일

    @Column(name = "password_enc", columnDefinition = "TEXT")
    private String passwordEnc;                 // 암호화된 비밀번호

    @Column(name = "name", columnDefinition = "TEXT")
    private String name;                        // 이름

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;                          // 시스템 권한 (USER/ADMIN)

    @Embedded
    private JobAssignment job;                  // 소속/직무 배정

    @Embedded
    private PersonalInfo personal;              // 개인 정보

    @Enumerated(EnumType.STRING)
    @Column(name = "emp_type")
    private EmpType empType;                    // 직원구분

    @Enumerated(EnumType.STRING)
    @Column(name = "hire_type")
    private HireType hireType;                  // 채용구분

    @Column(name = "hire_date")
    private LocalDate hireDate;                 // 입사일

    @Column(name = "recog_hire_date")
    private LocalDate recogHireDate;            // 인정입사일

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private EmploymentStatus status;            // 재적상태

    @Column(name = "resign_date")
    private LocalDate resignDate;               // 퇴사일자

    @Column(name = "resign_reason", columnDefinition = "TEXT")
    private String resignReason;                // 퇴사사유

    @Column(name = "leave_policy_id")
    private Long leavePolicyId;                 // 연차정책ID

    @Column(name = "work_group_id")
    private Long workGroupId;                   // 근무그룹ID

    @Builder
    private Employee(String empNo, String loginId, String passwordEnc, String name, Role role,
                     JobAssignment job, PersonalInfo personal,
                     EmpType empType, HireType hireType, LocalDate hireDate, LocalDate recogHireDate,
                     Long leavePolicyId, Long workGroupId) {
        this.empNo = empNo;
        this.loginId = loginId;
        this.passwordEnc = passwordEnc;
        this.name = name;
        this.role = role == null ? Role.USER : role;
        this.job = job;
        this.personal = personal;
        this.empType = empType;
        this.hireType = hireType;
        this.hireDate = hireDate;
        this.recogHireDate = recogHireDate;
        this.status = EmploymentStatus.EMPLOYED;
        this.leavePolicyId = leavePolicyId;
        this.workGroupId = workGroupId;
    }

    public boolean isTerminated() {
        return status == EmploymentStatus.TERMINATED;
    }

    /**
     * 이관 upsert 로 기존 사원 정보를 덮어쓴다. 호출 측이 기존 값과 합친(null 은 유지) 최종 값을 넘긴다.
     * 퇴직으로 바뀌면 호출 측(서비스)이 refresh 세션을 삭제해야 한다.
     */
    public void overwriteByMigration(String loginId, String name, JobAssignment job, PersonalInfo personal,
                                     EmpType empType, HireType hireType, LocalDate hireDate,
                                     LocalDate recogHireDate, EmploymentStatus status, LocalDate resignDate) {
        this.loginId = loginId;
        this.name = name;
        this.job = job;
        this.personal = personal;
        this.empType = empType;
        this.hireType = hireType;
        this.hireDate = hireDate;
        this.recogHireDate = recogHireDate;
        this.status = status;
        this.resignDate = resignDate;
    }

    /** 퇴직 처리. 호출 측(서비스)은 이후 이 사원의 refresh 세션을 삭제해야 한다. */
    public void terminate(LocalDate resignDate, String resignReason) {
        this.status = EmploymentStatus.TERMINATED;
        this.resignDate = resignDate;
        this.resignReason = resignReason;
    }
}
