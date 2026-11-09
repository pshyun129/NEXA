package com.ssafy.payrollservice.domain.payroll.service;

import com.ssafy.payrollservice.domain.payroll.dto.ContractResponse;
import com.ssafy.payrollservice.domain.payroll.dto.PayslipDetailResponse;
import com.ssafy.payrollservice.domain.payroll.dto.PayslipListResponse;
import com.ssafy.payrollservice.domain.payroll.dto.PayslipPdf;
import com.ssafy.payrollservice.domain.payroll.dto.RetirementEstimateResponse;

import java.time.LocalDate;

public interface PayslipService {

    /** 내 급여명세서 목록 (연도별, 최근 월부터) + 연간 합계 */
    PayslipListResponse getMyPayslips(Long empId, int year);

    /** 내 급여명세서 상세. 없거나 다른 사람 명세서면 404 */
    PayslipDetailResponse getMyPayslip(Long empId, Long payslipId);

    /** 내 급여명세서 PDF. 상세와 같은 내용, 없거나 다른 사람 명세서면 404 */
    PayslipPdf getMyPayslipPdf(Long empId, Long payslipId);

    /** 내 급여 정보. 토큰의 직급으로 현재 기본급 조회, 직급 기본급이 없으면 404 */
    ContractResponse getMyContract(Long gradeId);

    /** 예상 퇴직금. 퇴직월 이전 최근 명세서 3건 기준, 명세서가 없으면 404 */
    RetirementEstimateResponse getRetirementEstimate(Long empId, LocalDate resignDate);
}