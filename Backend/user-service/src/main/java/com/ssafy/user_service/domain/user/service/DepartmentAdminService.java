package com.ssafy.user_service.domain.user.service;

import com.ssafy.user_service.domain.user.dto.DepartmentResponse;
import com.ssafy.user_service.domain.user.entity.enums.DeptType;

import java.util.List;

/**
 * 부서 관리 (관리자 기능): 부서장 지정·해제, 부서 유형 변경.
 * 결재선은 상신할 때 조직 체인으로 정해지므로, 바꾼 값은 그 뒤에 상신하는 문서부터 적용된다
 */
public interface DepartmentAdminService {

    /** 전체 부서 (부서ID 순) */
    List<DepartmentResponse> findAll();

    /**
     * 부서장 지정·교체. 재직 중이고 그 부서나 하위 부서 소속인 사원만 (겸직: 팀장이 본부장을 겸하는 경우).
     * 없는 부서 404 DEPT_001, 없는 사원 404 EMP_001, 재직 아님 409 DEPT_002, 소속 아님 400 DEPT_003
     */
    DepartmentResponse assignHead(Long deptId, Long empId);

    /** 부서장 해제 (공석). 없는 부서 404 DEPT_001 */
    DepartmentResponse clearHead(Long deptId);

    /**
     * 부서 유형 변경. 최상위 부서만 COMPANY, 하위 부서는 상위 부서보다 높은 단계일 수 없다 (COMPANY > DIVISION > TEAM).
     * 없는 부서 404 DEPT_001, 규칙 위반 409 DEPT_004
     */
    DepartmentResponse changeType(Long deptId, DeptType deptType);
}
