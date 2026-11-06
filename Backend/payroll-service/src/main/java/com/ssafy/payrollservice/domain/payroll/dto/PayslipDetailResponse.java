package com.ssafy.payrollservice.domain.payroll.dto;

import com.ssafy.payrollservice.domain.payroll.entity.Payslip;
import com.ssafy.payrollservice.domain.payroll.entity.PayslipItem;
import com.ssafy.payrollservice.domain.payroll.entity.enums.PayItemKind;

import java.time.LocalDate;
import java.util.List;

/** 급여명세서 상세 */
public record PayslipDetailResponse(
        Long payslipId,             // 명세서 ID
        int payYear,                // 급여 연도
        int payMonth,               // 급여 월
        LocalDate payDate,          // 지급일
        Employee employee,          // 사원 정보 (생성 시점 스냅샷)
        WorkTime workTime,          // 근태 집계 (생성 시점 스냅샷)
        List<Item> earnings,        // 지급 항목 (0원 포함)
        List<Item> deductions,      // 공제 항목 (0원 포함)
        int totalPay,               // 지급합계
        int totalDeduction,         // 공제합계
        int netPay                  // 실지급액
) {

    public record Employee(
            String empName,         // 사원명
            String deptName,        // 부서명
            String positionName,    // 직급명 (직위 폐지로 직급을 표시. 이관한 지난 명세서는 원본의 직위명)
            LocalDate hireDate      // 입사일
    ) {
    }

    public record WorkTime(
            int workDays,           // 총 근무일수
            int totalMin,           // 총 근로시간(분)
            int regularMin,         // 기본 근로시간(분)
            int otExtendedMin,      // 연장 근로시간(분)
            int otNightMin,         // 야간 근로시간(분)
            int otHolidayMin,       // 휴일 근로시간(분)
            int leaveMin            // 휴가시간(분)
    ) {
    }

    public record Item(
            String itemCode,        // 급여항목 코드
            String name,            // 급여항목명
            int amount              // 금액
    ) {
        public static Item from(PayslipItem item) {
            return new Item(item.getPayItem().getItemCode(), item.getPayItem().getName(), item.getAmount());
        }
    }

    /** @param items 정렬 순서대로 조회한 명세서 항목 */
    public static PayslipDetailResponse of(Payslip payslip, List<PayslipItem> items) {
        return new PayslipDetailResponse(
                payslip.getPayslipId(),
                payslip.getPayYear(),
                payslip.getPayMonth(),
                payslip.getPayDate(),
                new Employee(
                        payslip.getEmpName(),
                        payslip.getDeptName(),
                        payslip.getPositionName(),
                        payslip.getHireDate()),
                new WorkTime(
                        payslip.getWorkDays(),
                        payslip.getTotalMin(),
                        payslip.getRegularMin(),
                        payslip.getOtExtendedMin(),
                        payslip.getOtNightMin(),
                        payslip.getOtHolidayMin(),
                        payslip.getLeaveMin()),
                itemsOf(items, PayItemKind.EARN),
                itemsOf(items, PayItemKind.DEDUCTION),
                payslip.getTotalPay(),
                payslip.getTotalDeduction(),
                payslip.getNetPay());
    }

    private static List<Item> itemsOf(List<PayslipItem> items, PayItemKind kind) {
        return items.stream()
                .filter(item -> item.getPayItem().getKind() == kind)
                .map(Item::from)
                .toList();
    }
}