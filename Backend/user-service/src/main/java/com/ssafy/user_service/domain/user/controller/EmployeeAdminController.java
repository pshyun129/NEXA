package com.ssafy.user_service.domain.user.controller;

import com.ssafy.user_service.domain.user.dto.*;
import com.ssafy.user_service.domain.user.entity.Employee;
import com.ssafy.user_service.domain.user.service.EmployeeInfoAssembler;
import com.ssafy.user_service.domain.user.service.EmployeeService;
import com.ssafy.user_service.global.security.AuthUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.ssafy.user_service.global.dto.ApiResult;
import com.ssafy.user_service.global.exception.BusinessException;
import com.ssafy.user_service.global.exception.ErrorCode;
import com.ssafy.user_service.global.security.SecurityConstants;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 사원 관리 API. 조회는 인증 사용자 모두, 등록(POST)/퇴직 처리(PATCH)는 SecurityConfig에서 ROLE_ADMIN 전용으로 묶인다. */
@RestController
@RequiredArgsConstructor
@RequestMapping(SecurityConstants.EMPLOYEE_BASE_PATH)
public class EmployeeAdminController {

    private final EmployeeService employeeService;
    private final EmployeeInfoAssembler employeeInfoAssembler;

    @PostMapping
    public ResponseEntity<ApiResult<EmployeeCreateResponse>> create(@Valid @RequestBody EmployeeCreateRequest request) {
        Long employeeId = employeeService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success(new EmployeeCreateResponse(employeeId)));
    }

    /**
     * 사원 목록. ids가 있으면 그 사원들만(없는 ID는 빠짐), deptId가 있으면 해당 부서원만, 둘 다 없으면 전체. 사원 ID 순.
     * page(0부터)·size(1~100, 기본 20)로 나눌 수 있다. 둘 다 없으면 전체 (ids 와는 함께 쓸 수 없다).
     * ids는 결재선 검증·이름 표시처럼 여러 사원을 한 번에 확인할 때 쓴다 (approval-service, 프런트엔드)
     */
    @GetMapping
    public ResponseEntity<ApiResult<EmployeeListResponse>> getList(@RequestParam(required = false) Long deptId,
                                                                   @RequestParam(required = false) List<Long> ids,
                                                                   @RequestParam(required = false) Integer page,
                                                                   @RequestParam(required = false) Integer size) {
        if (ids != null) {
            // ids 는 화면 목록이 아니라 여러 사원 일괄 확인용 (개수 제한 있음) 이라 다른 조건·페이지와 섞지 않는다
            if (deptId != null || page != null || size != null) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "ids는 deptId·page·size와 함께 쓸 수 없습니다.");
            }
            return ResponseEntity.ok(ApiResult.success(
                    EmployeeListResponse.all(employeeInfoAssembler.toResponses(employeeService.findAllByIds(ids)))));
        }
        Page<Employee> result = employeeService.findList(deptId, page, size);
        List<EmployeeInfoResponse> employees = employeeInfoAssembler.toResponses(result.getContent());
        EmployeeListResponse response = result.getPageable().isPaged()
                ? new EmployeeListResponse(employees, result.getTotalElements(), result.getNumber(), result.getSize())
                : EmployeeListResponse.all(employees);
        return ResponseEntity.ok(ApiResult.success(response));
    }

    /**
     * 로그인한 본인 정보 (소속 이름 포함). 신원은 access 토큰의 주체로만 정한다.
     * 퇴직 처리 후 남은 access 토큰으로 호출하면 로그인·재발급과 같은 401 AUTH_006.
     * 리터럴 경로라 /{employeeId} 보다 먼저 매칭된다
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResult<EmployeeInfoResponse>> getMe(@AuthenticationPrincipal AuthUser user) {
        Employee employee = employeeService.findById(user.employeeId())
                .orElseThrow(() -> new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND));
        if (employee.isTerminated()) {
            throw new BusinessException(ErrorCode.ACCOUNT_TERMINATED);
        }
        return ResponseEntity.ok(ApiResult.success(employeeInfoAssembler.toResponse(employee)));
    }

    @GetMapping("/{employeeId}")
    public ResponseEntity<ApiResult<EmployeeInfoResponse>> getEmployeeInfo(@PathVariable Long employeeId) {
        Employee employee = employeeService.findById(employeeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND));
        return ResponseEntity.ok(ApiResult.success(employeeInfoAssembler.toResponse(employee)));
    }

    @PatchMapping("/{employeeId}/terminate")
    public ResponseEntity<ApiResult<Void>> terminate(@PathVariable Long employeeId,
                                                     @Valid @RequestBody EmployeeTerminateRequest request) {
        employeeService.terminate(employeeId, request);
        return ResponseEntity.ok(ApiResult.success(null));
    }
}
