package com.ssafy.user_service.domain.user.controller;

import com.ssafy.user_service.domain.user.dto.DepartmentHeadRequest;
import com.ssafy.user_service.domain.user.dto.DepartmentResponse;
import com.ssafy.user_service.domain.user.dto.DepartmentTypeRequest;
import com.ssafy.user_service.domain.user.service.DepartmentAdminService;
import com.ssafy.user_service.global.dto.ApiResult;
import com.ssafy.user_service.global.security.SecurityConstants;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 부서 관리 API (관리자 전용: /api/v1/admin/** 는 SecurityConfig 에서 ROLE_ADMIN).
 * 부서장·부서 유형은 approval-service 가 위임전결 규정으로 결재선을 만들 때 쓴다
 */
@RestController
@RequiredArgsConstructor
@RequestMapping(SecurityConstants.ADMIN_BASE_PATH + "/departments")
public class DepartmentAdminController {

    private final DepartmentAdminService departmentAdminService;

    @GetMapping
    public ResponseEntity<ApiResult<List<DepartmentResponse>>> findAll() {
        return ResponseEntity.ok(ApiResult.success(departmentAdminService.findAll()));
    }

    /** 부서장 지정·교체 */
    @PutMapping("/{deptId}/head")
    public ResponseEntity<ApiResult<DepartmentResponse>> assignHead(@PathVariable Long deptId,
                                                                    @Valid @RequestBody DepartmentHeadRequest request) {
        return ResponseEntity.ok(ApiResult.success(departmentAdminService.assignHead(deptId, request.empId())));
    }

    /** 부서장 해제 (공석) */
    @DeleteMapping("/{deptId}/head")
    public ResponseEntity<ApiResult<DepartmentResponse>> clearHead(@PathVariable Long deptId) {
        return ResponseEntity.ok(ApiResult.success(departmentAdminService.clearHead(deptId)));
    }

    /** 부서 유형 변경 */
    @PutMapping("/{deptId}/type")
    public ResponseEntity<ApiResult<DepartmentResponse>> changeType(@PathVariable Long deptId,
                                                                    @Valid @RequestBody DepartmentTypeRequest request) {
        return ResponseEntity.ok(ApiResult.success(departmentAdminService.changeType(deptId, request.deptType())));
    }
}
