package com.ssafy.payrollservice.domain.payroll.dto;

import com.ssafy.payrollservice.domain.payroll.entity.Payslip;

import java.time.LocalDate;
import java.util.List;

/** 연도별 내 급여명세서 목록 */
public record PayslipListResponse(
        int year,               // 조회 연도
        List<Item> payslips,    // 명세서 목록 (최근 월부터)
        Total total             // 연간 합계
) {

    public record Item(
            Long payslipId,         // 명세서 ID
            int payMonth,           // 급여 월
            LocalDate payDate,      // 지급일
            int totalPay,           // 지급합계
            int totalDeduction,     // 공제합계
            int netPay              // 실지급액
    ) {
        public static Item from(Payslip payslip) {
            return new Item(
                    payslip.getPayslipId(),
                    payslip.getPayMonth(),
                    payslip.getPayDate(),
                    payslip.getTotalPay(),
                    payslip.getTotalDeduction(),
                    payslip.getNetPay());
        }
    }

    public record Total(
            int totalPay,           // 지급합계
            int totalDeduction,     // 공제합계
            int netPay              // 실지급액
    ) {
    }

    public static PayslipListResponse of(int year, List<Payslip> payslips) {
        List<Item> items = payslips.stream().map(Item::from).toList();

        Total total = new Total(
                items.stream().mapToInt(Item::totalPay).sum(),
                items.stream().mapToInt(Item::totalDeduction).sum(),
                items.stream().mapToInt(Item::netPay).sum());

        return new PayslipListResponse(year, items, total);
    }
}