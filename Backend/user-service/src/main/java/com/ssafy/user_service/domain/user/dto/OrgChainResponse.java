package com.ssafy.user_service.domain.user.dto;

import com.ssafy.user_service.domain.user.entity.enums.DeptType;

import java.util.List;

/**
 * 사원의 소속 부서부터 최상위 부서까지의 체인 (결재선 자동 결정용, approval-service 가 호출).
 * chain[0] 이 사원의 소속 부서, 마지막이 최상위 부서다.
 */
public record OrgChainResponse(
        Long employeeId,            // 사원ID
        Long deptId,                // 소속 부서ID
        List<OrgUnit> chain         // 소속 부서 → 상위 부서 순
) {
    /**
     * 부서 한 단계.
     * headEmpId: 재직(EMPLOYED) 중인 부서장만 담는다. 공석이거나 부서장이 휴직·퇴직이면 null
     */
    public record OrgUnit(
            Long deptId,            // 부서ID
            String deptName,        // 부서명
            DeptType deptType,      // 부서 유형 (값이 없으면 null)
            Long headEmpId          // 부서장 사원ID (공석이면 null)
    ) {
    }
}
