package com.ssafy.payrollservice.domain.payroll.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;

/** user-service 사원 정보 (급여명세서 생성·퇴직금 계산에 필요한 필드만) */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmployeeInfo(
        Long empId,             // 사원 ID
        String name,            // 이름
        String deptName,        // 부서명
        String positionName,    // 직위명 (없으면 null)
        Long gradeId,           // 직급 ID (pay_grade.grade_id)
        LocalDate hireDate      // 입사일
) {
}
