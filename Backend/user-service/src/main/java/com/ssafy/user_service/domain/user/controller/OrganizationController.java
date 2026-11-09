package com.ssafy.user_service.domain.user.controller;

import com.ssafy.user_service.domain.user.dto.OrgChainResponse;
import com.ssafy.user_service.domain.user.service.OrganizationService;
import com.ssafy.user_service.global.dto.ApiResult;
import com.ssafy.user_service.global.security.SecurityConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 조직 조회 API. 인증 사용자 모두 조회 가능 (사원 목록 조회와 같은 권한) */
@RestController
@RequiredArgsConstructor
@RequestMapping(SecurityConstants.EMPLOYEE_BASE_PATH)
public class OrganizationController {

    private final OrganizationService organizationService;

    /**
     * 사원의 소속 부서부터 최상위 부서까지의 체인과 각 단계의 부서장.
     * approval-service 가 위임전결 규정으로 결재선을 만들 때 호출한다
     */
    @GetMapping("/{employeeId}/org-chain")
    public ResponseEntity<ApiResult<OrgChainResponse>> getOrgChain(@PathVariable Long employeeId) {
        return ResponseEntity.ok(ApiResult.success(organizationService.getOrgChain(employeeId)));
    }
}
