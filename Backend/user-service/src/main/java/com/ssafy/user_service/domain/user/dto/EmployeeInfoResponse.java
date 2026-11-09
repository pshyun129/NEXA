package com.ssafy.user_service.domain.user.dto;

import com.ssafy.user_service.domain.user.entity.Employee;
import com.ssafy.user_service.domain.user.entity.JobAssignment;
import com.ssafy.user_service.domain.user.entity.enums.EmpType;
import com.ssafy.user_service.domain.user.entity.enums.EmploymentStatus;
import com.ssafy.user_service.domain.user.entity.enums.HireType;
import com.ssafy.user_service.global.security.Role;

import java.time.LocalDate;

/**
 * 사원 조회 응답. 개인정보(PersonalInfo)는 제외한다.
 * 소속 이름(deptName 등)은 EmployeeInfoAssembler 가 붙인다. 참조를 못 찾으면 null.
 * 다른 서비스(approval 등)도 이 응답을 쓰므로 필드는 추가만 하고 기존 필드는 바꾸지 않는다.
 */
public record EmployeeInfoResponse(
        Long empId,
        String empNo,
        String name,
        String loginId,
        Role role,
        Long deptId,
        Long positionId,
        Long gradeId,
        Long dutyId,
        EmpType empType,
        HireType hireType,
        LocalDate hireDate,
        EmploymentStatus status,
        String deptName,        // 부서명
        String positionName,    // 직위명
        String gradeName,       // 직급명
        String dutyName         // 직책명
) {
    public static EmployeeInfoResponse of(Employee employee, String deptName, String positionName,
                                          String gradeName, String dutyName) {
        JobAssignment job = employee.getJob();
        return new EmployeeInfoResponse(
                employee.getEmpId(),
                employee.getEmpNo(),
                employee.getName(),
                employee.getLoginId(),
                employee.getRole(),
                job.getDeptId(),
                job.getPositionId(),
                job.getGradeId(),
                job.getDutyId(),
                employee.getEmpType(),
                employee.getHireType(),
                employee.getHireDate(),
                employee.getStatus(),
                deptName,
                positionName,
                gradeName,
                dutyName
        );
    }
}
