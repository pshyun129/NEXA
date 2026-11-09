package com.ssafy.leaveservice.domain.department.controller;

import com.ssafy.leaveservice.domain.department.dto.DepartmentLeaveBalancesResponse;
import com.ssafy.leaveservice.domain.department.service.DepartmentLeaveService;
import com.ssafy.leaveservice.domain.department.dto.DepartmentLeaveScheduleResponse;
import com.ssafy.leaveservice.global.dto.ApiResult;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/leaves/departments/{departmentId}")
public class DepartmentLeaveController {
    private final DepartmentLeaveService departmentLeaveService;

    // 부서 소속 직원의 휴가 신청 목록 조회
    @GetMapping("/requests")
    public ResponseEntity<ApiResult<List<DepartmentLeaveScheduleResponse>>> getRequests(@PathVariable Long departmentId) {
        return ResponseEntity.ok(ApiResult.success(departmentLeaveService.getRequests(departmentId)));
    }

    // 부서 소속 직원의 휴가 잔액 조회
    @GetMapping("/balances")
    public ResponseEntity<ApiResult<DepartmentLeaveBalancesResponse>> getBalances(@PathVariable Long departmentId) {
        return ResponseEntity.ok(ApiResult.success(departmentLeaveService.getBalances(departmentId)));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResult<Void>> handleInvalidDepartmentId() {
        return ResponseEntity.badRequest().body(ApiResult.fail(ErrorCode.INVALID_INPUT));
    }
}
