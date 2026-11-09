package com.ssafy.leaveservice.domain.employee.controller;

import com.ssafy.leaveservice.domain.account.dto.MyLeaveBalanceResponse;
import com.ssafy.leaveservice.domain.account.service.MyLeaveBalanceService;
import com.ssafy.leaveservice.domain.grant.dto.MyLeaveGrantResponse;
import com.ssafy.leaveservice.domain.grant.service.MyLeaveGrantService;
import com.ssafy.leaveservice.domain.ledger.dto.MyLeaveLedgerResponse;
import com.ssafy.leaveservice.domain.ledger.service.MyLeaveLedgerService;
import com.ssafy.leaveservice.global.dto.ApiResult;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/leaves/admin/employees/{employeeId}")
@RequiredArgsConstructor
public class AdminEmployeeLeaveQueryController {

    private final MyLeaveBalanceService myLeaveBalanceService;
    private final MyLeaveGrantService myLeaveGrantService;
    private final MyLeaveLedgerService myLeaveLedgerService;

    // 직원별 휴가 잔액 조회
    @GetMapping("/balances")
    public ResponseEntity<ApiResult<List<MyLeaveBalanceResponse>>> getEmployeeBalances(@PathVariable Long employeeId) {
        List<MyLeaveBalanceResponse> response = myLeaveBalanceService.getEmployeeBalances(employeeId);
        return ResponseEntity.ok(ApiResult.success(response));
    }

    // 직원별 휴가 발생 내역 조회
    @GetMapping("/grants")
    public ResponseEntity<ApiResult<List<MyLeaveGrantResponse>>> getEmployeeGrants(@PathVariable Long employeeId) {
        List<MyLeaveGrantResponse> response = myLeaveGrantService.getEmployeeGrants(employeeId);
        return ResponseEntity.ok(ApiResult.success(response));
    }

    // 직원별 휴가 원장 조회
    @GetMapping("/ledger")
    public ResponseEntity<ApiResult<List<MyLeaveLedgerResponse>>> getEmployeeLedger(@PathVariable Long employeeId) {
        List<MyLeaveLedgerResponse> response = myLeaveLedgerService.getEmployeeLedger(employeeId);
        return ResponseEntity.ok(ApiResult.success(response));
    }

    // 기존 전역 예외 처리에 영향을 주지 않고 이 Controller의 잘못된 경로 형식만 400으로 응답한다.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResult<Void>> handleInvalidEmployeeId() {
        return ResponseEntity.badRequest().body(ApiResult.fail(ErrorCode.INVALID_INPUT));
    }
}
