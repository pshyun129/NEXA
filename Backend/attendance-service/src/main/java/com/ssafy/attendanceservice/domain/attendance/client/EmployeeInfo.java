package com.ssafy.attendanceservice.domain.attendance.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** user-service 사원 정보 (근태 현황에 필요한 필드만) */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmployeeInfo(
        Long empId,         // 사원 ID
        String name,        // 이름
        Long deptId,        // 부서 ID
        String deptName,    // 부서명
        Long gradeId,       // 직급 ID
        Long dutyId,        // 직책 ID
        String status       // 재직 상태: EMPLOYED / ON_LEAVE / TERMINATED
) {
}
