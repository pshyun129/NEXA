package com.ssafy.user_service.domain.user.entity;

import com.ssafy.user_service.domain.user.entity.enums.DeptType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "department")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "dept_id")
    private Long deptId;                // 부서ID

    @Column(name = "name", length = 255)
    private String name;                // 부서명

    @Column(name = "sort_order")
    private Integer sortOrder;          // 정렬번호

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;            // 회사

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_dept_id")
    private Department parent;          // 상위부서 (최상위 부서는 null)

    @Enumerated(EnumType.STRING)
    @Column(name = "dept_type", length = 20)
    private DeptType deptType;          // 부서 유형 (회사/본부/팀). 결재 전결 단계 기준

    @Column(name = "head_emp_id")
    private Long headEmpId;             // 부서장 사원ID (null 이면 공석)

    @Column(name = "dept_code", length = 50)
    private String deptCode;            // 외부 부서코드 (이관 기준 키). 직접 만든 부서는 null

    @Column(name = "pending_parent_code", length = 50)
    private String pendingParentCode;   // 이관 중 아직 못 찾은 상위 부서코드 (찾으면 연결 후 null)

    @Column(name = "pending_head_emp_no", length = 50)
    private String pendingHeadEmpNo;    // 이관 중 아직 없는 부서장 사번 (사원 적재 후 연결)

    @Builder
    private Department(String name, Integer sortOrder, Company company, Department parent,
                       DeptType deptType, Long headEmpId, String deptCode) {
        this.name = name;
        this.sortOrder = sortOrder;
        this.company = company;
        this.parent = parent;
        this.deptType = deptType;
        this.headEmpId = headEmpId;
        this.deptCode = deptCode;
    }

    /** 이관: 이름·정렬순서 변경 (null 은 유지) */
    public void updateBasics(String name, Integer sortOrder) {
        if (name != null) this.name = name;
        if (sortOrder != null) this.sortOrder = sortOrder;
    }

    /** 이관: 상위 부서 연결 (순환 검증은 서비스가 한다). 대기 중이던 상위 코드는 비운다 */
    public void linkParent(Department parent) {
        this.parent = parent;
        this.pendingParentCode = null;
    }

    /** 이관: 상위 부서코드가 아직 없어 연결을 미룬다 */
    public void deferParent(String parentCode) {
        this.pendingParentCode = parentCode;
    }

    /** 이관: 부서장 사번이 아직 없어 연결을 미룬다 */
    public void deferHead(String headEmpNo) {
        this.pendingHeadEmpNo = headEmpNo;
    }

    /** 이관: 대기 중이던 부서장 연결 */
    public void linkPendingHead(Long empId) {
        this.headEmpId = empId;
        this.pendingHeadEmpNo = null;
    }

    /** 부서장 지정·교체. 검증(재직·소속)은 서비스가 한다 */
    public void assignHead(Long empId) {
        this.headEmpId = empId;
    }

    /** 부서장 해제 (공석) */
    public void clearHead() {
        this.headEmpId = null;
    }

    /** 부서 유형 변경. 상위·하위 부서와의 순서 검증은 서비스가 한다 */
    public void changeType(DeptType deptType) {
        this.deptType = deptType;
    }
}
