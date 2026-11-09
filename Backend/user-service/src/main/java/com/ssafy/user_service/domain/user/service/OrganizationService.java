package com.ssafy.user_service.domain.user.service;

import com.ssafy.user_service.domain.user.dto.OrgChainResponse;

/** 조직(부서 계층·부서장) 조회 */
public interface OrganizationService {

    /**
     * 사원의 소속 부서부터 최상위 부서까지의 체인. 각 단계의 부서 유형과 재직 중인 부서장을 담는다.
     * 없는 사원이면 404 EMP_001, 소속 부서가 없으면 400 EMP_003
     */
    OrgChainResponse getOrgChain(Long employeeId);
}
