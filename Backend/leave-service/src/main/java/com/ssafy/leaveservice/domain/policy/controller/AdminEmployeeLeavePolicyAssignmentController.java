package com.ssafy.leaveservice.domain.policy.controller;

import com.ssafy.leaveservice.domain.policy.dto.EmployeeLeavePolicyAssignmentResponse;
import com.ssafy.leaveservice.domain.policy.dto.EmployeeLeavePolicyAssignmentCreateRequest;
import com.ssafy.leaveservice.domain.policy.service.EmployeeLeavePolicyAssignmentService;
import com.ssafy.leaveservice.global.dto.ApiResult;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/leaves/admin/employees/{employeeId}/policy-assignments")
@RequiredArgsConstructor
public class AdminEmployeeLeavePolicyAssignmentController {

    private final EmployeeLeavePolicyAssignmentService employeeLeavePolicyAssignmentService;

    // 직원에게 휴가 정책 배정
    @PostMapping
    public ResponseEntity<ApiResult<EmployeeLeavePolicyAssignmentResponse>> createPolicyAssignment(
            @PathVariable Long employeeId,
            @Valid @RequestBody EmployeeLeavePolicyAssignmentCreateRequest request
    ) {
        EmployeeLeavePolicyAssignmentResponse response = employeeLeavePolicyAssignmentService
                .createPolicyAssignment(employeeId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.success(response));
    }

    // 직원의 휴가 정책 배정 이력 조회
    @GetMapping
    public ResponseEntity<ApiResult<List<EmployeeLeavePolicyAssignmentResponse>>> getPolicyAssignments(
            @PathVariable Long employeeId
    ) {
        List<EmployeeLeavePolicyAssignmentResponse> response = employeeLeavePolicyAssignmentService
                .getPolicyAssignments(employeeId);
        return ResponseEntity.ok(ApiResult.success(response));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResult<Void>> handleInvalidEmployeeIdType() {
        return ResponseEntity.badRequest().body(ApiResult.fail(ErrorCode.INVALID_INPUT));
    }
}
