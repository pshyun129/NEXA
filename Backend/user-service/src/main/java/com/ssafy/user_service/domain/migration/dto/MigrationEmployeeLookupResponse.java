package com.ssafy.user_service.domain.migration.dto;

import java.time.LocalDate;

/**
 * 사번 → 사원 ID와 명세서에 남길 사원 정보. 급여처럼 사원 ID로 저장하는 서비스로 이관할 때 migration 이 쓴다.
 * 없는 사번은 응답에 빠진다.
 */
public record MigrationEmployeeLookupResponse(
        String empNo,
        Long empId,
        String name,
        String deptName,
        String positionName,
        Long gradeId,
        LocalDate hireDate
) {
}
