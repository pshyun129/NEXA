package com.ssafy.payrollservice.domain.payroll.service;

import com.ssafy.payrollservice.domain.payroll.client.EmployeeInfo;
import com.ssafy.payrollservice.domain.payroll.client.UserClient;
import com.ssafy.payrollservice.domain.payroll.dto.RetirementEstimateResponse;
import com.ssafy.payrollservice.domain.payroll.entity.Payslip;
import com.ssafy.payrollservice.domain.payroll.pdf.PayslipPdfRenderer;
import com.ssafy.payrollservice.domain.payroll.repository.PayGradeRepository;
import com.ssafy.payrollservice.domain.payroll.repository.PayslipItemRepository;
import com.ssafy.payrollservice.domain.payroll.repository.PayslipRepository;
import com.ssafy.payrollservice.global.exception.BusinessException;
import com.ssafy.payrollservice.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PayslipServiceImplRetirementTest {

    private static final long EMP_ID = 103L;

    private PayslipRepository payslipRepository;
    private PayslipItemRepository payslipItemRepository;
    private UserClient userClient;
    private PayslipServiceImpl service;

    @BeforeEach
    void setUp() {
        payslipRepository = mock(PayslipRepository.class);
        payslipItemRepository = mock(PayslipItemRepository.class);
        userClient = mock(UserClient.class);
        service = new PayslipServiceImpl(payslipRepository, payslipItemRepository,
                mock(PayGradeRepository.class), mock(PayslipPdfRenderer.class), userClient);
    }

    private void hiredOn(LocalDate hireDate) {
        when(userClient.getEmployee(EMP_ID))
                .thenReturn(new EmployeeInfo(EMP_ID, "정하은", "개발팀", "G1", 1L, hireDate));
    }

    private static Payslip payslip(long id, int month, int totalPay) {
        Payslip p = Payslip.builder().empId(EMP_ID).payYear((short) 2026).payMonth((short) month)
                .totalPay(totalPay).build();
        ReflectionTestUtils.setField(p, "payslipId", id);
        return p;
    }

    @Test
    @DisplayName("3개월 임금은 지급합계에서 상여·명절수당·연차수당을 뺀 값 (상여는 3/12로만 반영)")
    void bonusIsNotCountedTwice() {
        hiredOn(LocalDate.of(2025, 7, 1));
        // 9월 명세서에 상여 3,600,000 + 명절수당 1,800,000이 들어 있음
        when(payslipRepository.findRecentBefore(eq(EMP_ID), anyInt(), any())).thenReturn(List.of(
                payslip(3, 11, 3_600_000), payslip(2, 10, 3_600_000), payslip(1, 9, 9_000_000)));
        when(payslipItemRepository.sumAmountOfPayslips(eq(List.of(3L, 2L, 1L)), anyList()))
                .thenReturn(5_400_000L);
        when(payslipItemRepository.sumAmount(eq(EMP_ID), eq("BONUS"), anyInt(), anyInt())).thenReturn(3_600_000L);
        when(payslipItemRepository.sumAmount(eq(EMP_ID), eq("HOLIDAY_BONUS"), anyInt(), anyInt())).thenReturn(1_800_000L);
        when(payslipItemRepository.sumAmount(eq(EMP_ID), eq("ANNUAL_LEAVE_PAY"), anyInt(), anyInt())).thenReturn(0L);

        RetirementEstimateResponse r = service.getRetirementEstimate(EMP_ID, LocalDate.of(2026, 12, 1));

        // 3개월 임금 = 16,200,000 − 5,400,000 = 10,800,000 (상여가 여기 들어가면 이중 반영)
        assertEquals(10_800_000, r.threeMonthPay());
        // 상여 3/12에는 상여금 + 명절·휴가수당
        assertEquals(5_400_000, r.annualBonus());
        // (10,800,000 + 5,400,000 × 3/12) ÷ 91일(9~11월) = 133,516
        assertEquals(91, r.periodDays());
        assertEquals(133_516, r.dailyAverageWage());
    }

    @Test
    @DisplayName("근속 1년 미만이면 명세서가 없어도 PAY_007이 아니라 퇴직금 0원")
    void underOneYearWithoutPayslips() {
        hiredOn(LocalDate.of(2025, 7, 1));
        when(payslipRepository.findRecentBefore(eq(EMP_ID), anyInt(), any())).thenReturn(List.of());

        RetirementEstimateResponse r = service.getRetirementEstimate(EMP_ID, LocalDate.of(2026, 1, 1));

        assertEquals(0, r.retirementPay());
        assertEquals(184, r.serviceDays());
        assertEquals(0, r.basePayslipCount());
    }

    @Test
    @DisplayName("근속 1년 이상인데 명세서가 없으면 PAY_007")
    void overOneYearWithoutPayslips() {
        hiredOn(LocalDate.of(2024, 7, 1));
        when(payslipRepository.findRecentBefore(eq(EMP_ID), anyInt(), any())).thenReturn(List.of());

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.getRetirementEstimate(EMP_ID, LocalDate.of(2026, 1, 1)));
        assertEquals(ErrorCode.RETIREMENT_PAYSLIP_NOT_FOUND, e.getErrorCode());
    }
}
