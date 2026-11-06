package com.ssafy.payrollservice.domain.payroll.service;

import com.ssafy.payrollservice.domain.payroll.calculator.RetirementCalculator;
import com.ssafy.payrollservice.domain.payroll.calculator.RetirementResult;
import com.ssafy.payrollservice.domain.payroll.client.UserClient;
import com.ssafy.payrollservice.domain.payroll.dto.ContractResponse;
import com.ssafy.payrollservice.domain.payroll.dto.PayslipDetailResponse;
import com.ssafy.payrollservice.domain.payroll.dto.PayslipListResponse;
import com.ssafy.payrollservice.domain.payroll.dto.PayslipPdf;
import com.ssafy.payrollservice.domain.payroll.dto.RetirementEstimateResponse;
import com.ssafy.payrollservice.domain.payroll.entity.Payslip;
import com.ssafy.payrollservice.domain.payroll.pdf.PayslipPdfRenderer;
import com.ssafy.payrollservice.domain.payroll.repository.PayGradeRepository;
import com.ssafy.payrollservice.domain.payroll.repository.PayslipItemRepository;
import com.ssafy.payrollservice.domain.payroll.repository.PayslipRepository;
import com.ssafy.payrollservice.global.exception.BusinessException;
import com.ssafy.payrollservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PayslipServiceImpl implements PayslipService {

    private final PayslipRepository payslipRepository;
    private final PayslipItemRepository payslipItemRepository;
    private final PayGradeRepository payGradeRepository;
    private final PayslipPdfRenderer payslipPdfRenderer;
    private final UserClient userClient;

    @Override
    @Transactional(readOnly = true)
    public PayslipListResponse getMyPayslips(Long empId, int year) {
        return PayslipListResponse.of(year,
                payslipRepository.findByEmpIdAndPayYearOrderByPayMonthDesc(empId, (short) year));
    }

    @Override
    @Transactional(readOnly = true)
    public PayslipDetailResponse getMyPayslip(Long empId, Long payslipId) {
        // 없는 명세서와 다른 사람 명세서를 같은 404로 응답 (존재 여부 노출 방지)
        Payslip payslip = payslipRepository.findById(payslipId)
                .filter(p -> p.getEmpId().equals(empId))
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYSLIP_NOT_FOUND));

        return PayslipDetailResponse.of(payslip, payslipItemRepository.findWithPayItemByPayslipId(payslipId));
    }

    @Override
    @Transactional(readOnly = true)
    public PayslipPdf getMyPayslipPdf(Long empId, Long payslipId) {
        // 상세 조회와 같은 권한 확인 후 그 내용을 그대로 PDF로
        PayslipDetailResponse payslip = getMyPayslip(empId, payslipId);
        return PayslipPdf.of(payslip, payslipPdfRenderer.render(payslip));
    }

    @Override
    @Transactional(readOnly = true)
    public ContractResponse getMyContract(Long gradeId) {
        // 직급 claim이 없는 토큰 (1.1.0 이전 발급분)
        if (gradeId == null) {
            throw new BusinessException(ErrorCode.PAY_GRADE_NOT_FOUND, "토큰에 직급 정보가 없습니다. 다시 로그인해 주세요.");
        }
        return payGradeRepository.findById(gradeId)
                .map(ContractResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAY_GRADE_NOT_FOUND));
    }

    private static final int BASE_MONTHS = 3;   // 평균임금 산정 개월 수

    @Override
    @Transactional(readOnly = true)
    public RetirementEstimateResponse getRetirementEstimate(Long empId, LocalDate resignDate) {
        YearMonth resignMonth = YearMonth.from(resignDate);

        // 1. 퇴직월 이전 최근 명세서 3건 (예상 퇴직일이 미래면 지금 있는 최근 3건)
        List<Payslip> recent = payslipRepository.findRecentBefore(
                empId, toYearMonth(resignMonth), PageRequest.of(0, BASE_MONTHS));
        if (recent.isEmpty()) {
            throw new BusinessException(ErrorCode.RETIREMENT_PAYSLIP_NOT_FOUND);
        }

        // 입사일은 지금 사원 정보 기준 (명세서의 입사일은 생성 시점 스냅샷)
        LocalDate hireDate = userClient.getEmployee(empId).hireDate();
        if (!resignDate.isAfter(hireDate)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "퇴직일은 입사일 이후여야 합니다.");
        }

        // 2. 3개월 임금. 명세서가 3건보다 적으면 월평균 × 3으로 환산
        long payTotal = recent.stream().mapToLong(Payslip::getTotalPay).sum();
        int threeMonthPay = (int) (payTotal * BASE_MONTHS / recent.size());

        // 3. 퇴직월 직전 12개월의 상여금, 연차수당
        int from = toYearMonth(resignMonth.minusMonths(12));
        int to = toYearMonth(resignMonth.minusMonths(1));
        int annualBonus = (int) payslipItemRepository.sumAmount(empId, "BONUS", from, to);
        int annualLeavePay = (int) payslipItemRepository.sumAmount(empId, "ANNUAL_LEAVE_PAY", from, to);

        RetirementResult result = RetirementCalculator.calculate(
                hireDate, resignDate, threeMonthPay, annualBonus, annualLeavePay);

        return RetirementEstimateResponse.of(hireDate, resignDate, recent.size(), result);
    }

    /** 2026-10 → 202610 */
    private static int toYearMonth(YearMonth yearMonth) {
        return yearMonth.getYear() * 100 + yearMonth.getMonthValue();
    }
}