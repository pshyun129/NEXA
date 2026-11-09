package com.ssafy.payrollservice.domain.payroll.controller;

import com.ssafy.payrollservice.domain.payroll.dto.PayslipGenerateRequest;
import com.ssafy.payrollservice.domain.payroll.dto.PayslipGenerateResponse;
import com.ssafy.payrollservice.domain.payroll.service.AdminPayslipService;
import com.ssafy.payrollservice.global.dto.ApiResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 급여 API */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/pay/admin")
public class AdminPayslipController {

    private final AdminPayslipService adminPayslipService;

    // 월 급여 생성 API
    @PostMapping("/payslips/generate")
    public ResponseEntity<ApiResult<PayslipGenerateResponse>> generate(
            @Valid @RequestBody PayslipGenerateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success(adminPayslipService.generate(request)));
    }
}