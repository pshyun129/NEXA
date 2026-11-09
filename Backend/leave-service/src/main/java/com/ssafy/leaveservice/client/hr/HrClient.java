package com.ssafy.leaveservice.client.hr;

import java.util.List;

/** 내부 조회 계약. URI·외부 DTO·Bearer 전달은 향후 구현체에서 매핑한다. */
public interface HrClient {
    boolean isAvailable();

    EmployeeInfo findEmployee(Long employeeId);

    MemberBatch findDepartmentMembers(Long departmentId);

    DutyInfo findDuty(Long dutyId);

    default OrganizationChain findOrganizationChain(Long employeeId) {
        throw new com.ssafy.leaveservice.global.exception.BusinessException(
                com.ssafy.leaveservice.global.exception.ErrorCode.HR_VERIFICATION_UNAVAILABLE);
    }

    record OrganizationChain(Long employeeId, Long deptId, List<OrganizationUnit> chain) {}
    record OrganizationUnit(Long deptId, String deptName, String deptType, Long headEmpId) {}

    record EmployeeInfo(Long employeeId, Long departmentId, Long dutyId, Long positionId,
                        Long gradeId, EmploymentStatus status) {}
    enum EmploymentStatus { EMPLOYED, ON_LEAVE, TERMINATED }
    record Member(Long employeeId, Long departmentId, EmploymentStatus status) {}
    record MemberBatch(List<Member> members, boolean complete) {}
    record DutyInfo(Long dutyId, String name) {}
}
