package com.ssafy.payrollservice.domain.payroll.service;

import com.ssafy.payrollservice.domain.payroll.calculator.DeductionCalculator;
import com.ssafy.payrollservice.domain.payroll.calculator.DeductionResult;
import com.ssafy.payrollservice.domain.payroll.calculator.EarningCalculator;
import com.ssafy.payrollservice.domain.payroll.calculator.EarningResult;
import com.ssafy.payrollservice.domain.payroll.calculator.PayDateCalculator;
import com.ssafy.payrollservice.domain.payroll.client.AttendanceClient;
import com.ssafy.payrollservice.domain.payroll.client.AttendanceSummary;
import com.ssafy.payrollservice.domain.payroll.client.EmployeeInfo;
import com.ssafy.payrollservice.domain.payroll.client.UserClient;
import com.ssafy.payrollservice.domain.payroll.dto.PayslipGenerateRequest;
import com.ssafy.payrollservice.domain.payroll.dto.PayslipGenerateResponse;
import com.ssafy.payrollservice.domain.payroll.entity.PayGrade;
import com.ssafy.payrollservice.domain.payroll.entity.PayItem;
import com.ssafy.payrollservice.domain.payroll.entity.Payslip;
import com.ssafy.payrollservice.domain.payroll.entity.PayslipItem;
import com.ssafy.payrollservice.domain.payroll.repository.PayGradeRepository;
import com.ssafy.payrollservice.domain.payroll.repository.PayItemRepository;
import com.ssafy.payrollservice.domain.payroll.repository.PayslipItemRepository;
import com.ssafy.payrollservice.domain.payroll.repository.PayslipRepository;
import com.ssafy.payrollservice.global.exception.BusinessException;
import com.ssafy.payrollservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminPayslipServiceImpl implements AdminPayslipService {

    private static final Set<String> HOLIDAY_BONUS_NAMES = Set.of("설날", "추석");  // 명절수당 기준 공휴일명

    private final PayslipRepository payslipRepository;
    private final PayslipItemRepository payslipItemRepository;
    private final PayGradeRepository payGradeRepository;
    private final PayItemRepository payItemRepository;
    private final AttendanceClient attendanceClient;
    private final UserClient userClient;

    @Override
    @Transactional
    public PayslipGenerateResponse generate(PayslipGenerateRequest request) {
        YearMonth month = request.month();
        Short payYear = (short) month.getYear();
        Short payMonth = (short) month.getMonthValue();

        // 1. 같은 사원의 같은 달 명세서는 하나
        if (payslipRepository.existsByEmpIdAndPayYearAndPayMonth(request.empId(), payYear, payMonth)) {
            throw new BusinessException(ErrorCode.PAYSLIP_ALREADY_EXISTS);
        }

        // 2. 사원 정보 (user-service, 요청한 관리자의 토큰으로)
        EmployeeInfo employee = userClient.getEmployee(request.empId());
        if (employee.gradeId() == null) {
            throw new BusinessException(ErrorCode.PAY_GRADE_NOT_FOUND, "사원의 직급이 지정되지 않았습니다.");
        }

        // 3. 직급 기본급
        PayGrade grade = payGradeRepository.findById(employee.gradeId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAY_GRADE_NOT_FOUND));

        // 4. 근태 월 집계 (미확정일이 있어도 그대로 생성)
        AttendanceSummary attendance = attendanceClient.getSummary(request.empId(), month);
        AttendanceSummary.WorkTime workTime = attendance.workTime();

        // 5. 지급 → 공제 계산 (승인 여부와 관계없이 실제 일한 시간 기준, 무급휴가는 지급에서 차감,
        //    분기 마지막 달은 상여금, 설날·추석이 있는 달은 명절수당)
        List<AttendanceClient.Holiday> holidays = attendanceClient.getHolidays(month).stream()
                .filter(AttendanceClient.Holiday::isCompanyWide)
                .toList();
        EarningResult earning = EarningCalculator.calculate(grade.getBaseAmount(),
                workTime.otExtendedMin(), attendance.nightTotalMin(), workTime.otHolidayMin(),
                attendance.unpaidLeaveMin(), isQuarterEnd(month), isHolidayMonth(holidays));
        DeductionResult deduction = DeductionCalculator.calculate(earning.total());

        // 6. 명세서 저장 (생성 시점의 사원 정보, 근태 집계 스냅샷)
        Payslip payslip = payslipRepository.save(Payslip.builder()
                .empId(request.empId())
                .payYear(payYear)
                .payMonth(payMonth)
                .payDate(PayDateCalculator.payDateOf(month, holidays.stream()
                        .map(AttendanceClient.Holiday::holidayDate)
                        .collect(Collectors.toSet())))
                .empName(employee.name())
                .deptName(employee.deptName())
                .positionName(employee.gradeName())    // 직위 폐지로 직급명을 표시 (컬럼명은 그대로)
                .hireDate(employee.hireDate())
                .workDays((short) attendance.workedDays())
                .totalMin(workTime.totalMin())
                .regularMin(workTime.regularMin())
                .otExtendedMin(workTime.otExtendedMin())
                .otNightMin(attendance.nightTotalMin())
                .otHolidayMin(workTime.otHolidayMin())
                .leaveMin(attendance.leaveMin())
                .totalPay(earning.total())
                .totalDeduction(deduction.total())
                .netPay(earning.total() - deduction.total())
                .build());

        // 7. 항목 14개 저장 (0원 항목 포함)
        Map<String, PayItem> payItems = payItemRepository.findAll().stream()
                .collect(Collectors.toMap(PayItem::getItemCode, Function.identity()));

        List<PayslipItem> items = toItemAmounts(earning, deduction).entrySet().stream()
                .map(e -> PayslipItem.builder()
                        .payslip(payslip)
                        .payItem(getPayItem(payItems, e.getKey()))
                        .amount(e.getValue())
                        .build())
                .toList();
        payslipItemRepository.saveAll(items);

        return PayslipGenerateResponse.from(payslip);
    }

    /** 분기 마지막 달 (3·6·9·12월) */
    private static boolean isQuarterEnd(YearMonth month) {
        return month.getMonthValue() % 3 == 0;
    }

    /** 설날·추석 당일이 있는 달 (근태 공휴일 기준, 연휴가 두 달에 걸쳐도 당일이 있는 달만) */
    private static boolean isHolidayMonth(List<AttendanceClient.Holiday> holidays) {
        return holidays.stream()
                .anyMatch(holiday -> HOLIDAY_BONUS_NAMES.contains(holiday.name()));
    }

    /** pay_item.item_code → 금액 */
    private static Map<String, Integer> toItemAmounts(EarningResult earning, DeductionResult deduction) {
        return Map.ofEntries(
                // 지급
                Map.entry("BASE_PAY", earning.basePay()),
                Map.entry("OT_EXTENDED_PAY", earning.otExtendedPay()),
                Map.entry("OT_NIGHT_PAY", earning.otNightPay()),
                Map.entry("OT_HOLIDAY_PAY", earning.otHolidayPay()),
                Map.entry("ANNUAL_LEAVE_PAY", earning.annualLeavePay()),
                Map.entry("BONUS", earning.bonus()),
                Map.entry("HOLIDAY_BONUS", earning.holidayBonus()),
                Map.entry("UNPAID_LEAVE_DEDUCTION", earning.unpaidLeaveDeduction()),
                // 공제
                Map.entry("INCOME_TAX", deduction.incomeTax()),
                Map.entry("LOCAL_INCOME_TAX", deduction.localIncomeTax()),
                Map.entry("NATIONAL_PENSION", deduction.nationalPension()),
                Map.entry("HEALTH_INSURANCE", deduction.healthInsurance()),
                Map.entry("LONG_TERM_CARE", deduction.longTermCare()),
                Map.entry("EMPLOYMENT_INSURANCE", deduction.employmentInsurance()));
    }

    private static PayItem getPayItem(Map<String, PayItem> payItems, String itemCode) {
        PayItem item = payItems.get(itemCode);
        if (item == null) {
            throw new BusinessException(ErrorCode.PAY_ITEM_NOT_FOUND, "급여 항목이 설정되지 않았습니다: " + itemCode);
        }
        return item;
    }
}