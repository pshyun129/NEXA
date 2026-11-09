package com.ssafy.user_service.domain.user.dto;

import com.ssafy.user_service.domain.user.entity.Department;
import com.ssafy.user_service.domain.user.entity.enums.DeptType;

/**
 * 부서 관리 응답 (관리자 화면용).
 * headEmpId 는 저장된 값 그대로다. 부서장이 휴직·퇴직이어도 남아 있을 수 있다 (결재선에서는 공석으로 본다)
 */
public record DepartmentResponse(
        Long deptId,            // 부서ID
        String name,            // 부서명
        Integer sortOrder,      // 정렬번호
        Long parentDeptId,      // 상위 부서ID (최상위면 null)
        DeptType deptType,      // 부서 유형 (값이 없으면 null)
        Long headEmpId          // 부서장 사원ID (공석이면 null)
) {
    public static DepartmentResponse from(Department department) {
        Department parent = department.getParent();
        return new DepartmentResponse(
                department.getDeptId(),
                department.getName(),
                department.getSortOrder(),
                parent == null ? null : parent.getDeptId(),
                department.getDeptType(),
                department.getHeadEmpId());
    }
}
