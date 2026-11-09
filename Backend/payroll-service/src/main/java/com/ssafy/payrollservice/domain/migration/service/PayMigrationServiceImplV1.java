package com.ssafy.payrollservice.domain.migration.service;

import com.ssafy.payrollservice.domain.migration.dto.MigrationBatchSummaryResponse;
import com.ssafy.payrollservice.domain.migration.dto.MigrationUpsertRequest;
import com.ssafy.payrollservice.domain.migration.dto.MigrationUpsertResponse;
import com.ssafy.payrollservice.domain.migration.repository.MigrationBatchRowRepository;
import com.ssafy.payrollservice.domain.migration.repository.MigrationBatchRowRepository.Action;
import com.ssafy.payrollservice.domain.migration.repository.MigrationBatchRowRepository.Entry;
import com.ssafy.payrollservice.domain.migration.repository.MigrationBatchRowRepository.Target;
import com.ssafy.payrollservice.domain.payroll.entity.PayGrade;
import com.ssafy.payrollservice.domain.payroll.entity.PayItem;
import com.ssafy.payrollservice.domain.payroll.entity.Payslip;
import com.ssafy.payrollservice.domain.payroll.entity.PayslipItem;
import com.ssafy.payrollservice.domain.payroll.repository.PayGradeRepository;
import com.ssafy.payrollservice.domain.payroll.repository.PayItemRepository;
import com.ssafy.payrollservice.domain.payroll.repository.PayslipItemRepository;
import com.ssafy.payrollservice.domain.payroll.repository.PayslipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 급여 이관. 기본급은 직급 ID, 명세서는 사원 ID로 온다 (migration 이 user-service 에서 찾아 채운다).
 * 지난 명세서는 계산하지 않고 급여대장 값 그대로 저장한다. 근태 시간은 연장 근로시간만 오고 나머지는 0.
 */
@Service
@RequiredArgsConstructor
public class PayMigrationServiceImplV1 implements PayMigrationService {

    private static final Map<String, String> GRADE_LABELS = Map.of("gradeId", "직급 ID", "baseAmount", "기본급");
    private static final Map<String, String> PAYSLIP_LABELS = Map.ofEntries(
            Map.entry("empId", "사원 ID"), Map.entry("payYear", "귀속연도"), Map.entry("payMonth", "귀속월"),
            Map.entry("payDate", "지급일"), Map.entry("empName", "사원명"), Map.entry("deptName", "부서명"),
            Map.entry("positionName", "직위명"), Map.entry("hireDate", "입사일"), Map.entry("otExtendedMin", "연장 근로시간(분)"),
            Map.entry("totalPay", "지급합계"), Map.entry("totalDeduction", "공제합계"), Map.entry("netPay", "실지급액"),
            Map.entry("items", "급여 항목"));

    private final PayGradeRepository payGradeRepository;
    private final PayslipRepository payslipRepository;
    private final PayslipItemRepository payslipItemRepository;
    private final PayItemRepository payItemRepository;
    private final MigrationBatchRowRepository batchRowRepository;

    @Override
    @Transactional
    public MigrationUpsertResponse upsertGrades(String batchId, MigrationUpsertRequest request) {
        int created = 0;
        for (MigrationUpsertRequest.Row raw : request.rows()) {
            MigrationRow row = new MigrationRow(raw, GRADE_LABELS);
            long gradeId = row.requiredLong("gradeId");
            Integer amount = row.integer("baseAmount");
            if (amount == null || amount <= 0) {
                throw row.error("기본급은 0보다 커야 합니다");
            }
            if (payGradeRepository.existsById(gradeId)) {
                batchRowRepository.record(batchId, Target.PAY_GRADE, gradeId, Action.EXISTING, row.rowNo());
                continue;
            }
            payGradeRepository.save(PayGrade.builder().gradeId(gradeId).baseAmount(amount).build());
            batchRowRepository.record(batchId, Target.PAY_GRADE, gradeId, Action.CREATED, row.rowNo());
            created++;
        }
        return new MigrationUpsertResponse(created, request.rows().size() - created);
    }

    @Override
    @Transactional
    public MigrationUpsertResponse upsertPayslips(String batchId, MigrationUpsertRequest request) {
        Map<String, PayItem> payItems = payItemRepository.findAll().stream()
                .collect(Collectors.toMap(PayItem::getItemCode, Function.identity()));
        for (MigrationUpsertRequest.Row raw : request.rows()) {
            MigrationRow row = new MigrationRow(raw, PAYSLIP_LABELS);
            long empId = row.requiredLong("empId");
            short year = (short) row.requiredLong("payYear");
            Integer month = row.integer("payMonth");
            if (month == null || month < 1 || month > 12) {
                throw row.error("귀속월은 1~12여야 합니다");
            }
            if (payslipRepository.existsByEmpIdAndPayYearAndPayMonth(empId, year, month.shortValue())) {
                throw row.error(year + "-" + month + " 급여명세서가 이미 있습니다 (사원 ID " + empId + ")");
            }
            Payslip payslip = payslipRepository.save(Payslip.builder()
                    .empId(empId).payYear(year).payMonth(month.shortValue()).payDate(row.date("payDate"))
                    .empName(row.requiredString("empName")).deptName(row.requiredString("deptName"))
                    .positionName(row.string("positionName"))
                    .hireDate(required(row, row.date("hireDate"), "hireDate"))
                    .workDays((short) 0).totalMin(0).regularMin(0)
                    .otExtendedMin(row.integer("otExtendedMin") == null ? 0 : row.integer("otExtendedMin"))
                    .otNightMin(0).otHolidayMin(0).leaveMin(0)
                    .totalPay(row.integer("totalPay")).totalDeduction(row.integer("totalDeduction")).netPay(row.integer("netPay"))
                    .build());
            List<PayslipItem> items = items(row, payItems).entrySet().stream()
                    .map(e -> PayslipItem.builder().payslip(payslip).payItem(e.getKey()).amount(e.getValue()).build())
                    .toList();
            payslipItemRepository.saveAll(items);
            batchRowRepository.record(batchId, Target.PAYSLIP, payslip.getPayslipId(), Action.CREATED, row.rowNo());
        }
        return new MigrationUpsertResponse(request.rows().size(), 0);
    }

    /** items: {pay_item.item_code: 금액}. 0원·빈 항목은 넣지 않는다 */
    private static Map<PayItem, Integer> items(MigrationRow row, Map<String, PayItem> payItems) {
        if (!(row.raw("items") instanceof Map<?, ?> raw)) {
            throw row.error("급여 항목(items)이 없습니다");
        }
        Map<PayItem, Integer> out = new LinkedHashMap<>();
        raw.forEach((code, value) -> {
            PayItem item = payItems.get(String.valueOf(code));
            if (item == null) {
                throw row.error("급여 항목 코드 '" + code + "'을(를) 알 수 없습니다");
            }
            if (value == null || value.toString().isBlank()) {
                return;
            }
            try {
                int amount = new BigDecimal(value.toString().replace(",", "")).intValueExact();
                if (amount != 0) {
                    out.put(item, amount);
                }
            } catch (ArithmeticException | NumberFormatException e) {
                throw row.error(item.getName() + " 금액 '" + value + "'은(는) 정수가 아닙니다");
            }
        });
        return out;
    }

    private static <T> T required(MigrationRow row, T value, String key) {
        if (value == null) {
            throw row.error(row.label(key) + " 값이 없습니다");
        }
        return value;
    }

    @Override
    @Transactional
    public void rollback(String batchId) {
        for (Entry e : batchRowRepository.entries(batchId)) {
            if (e.action() != Action.CREATED) {
                continue;
            }
            if (e.target() == Target.PAYSLIP) {
                batchRowRepository.deletePayslip(e.targetId());
            } else {
                batchRowRepository.deletePayGrade(e.targetId());
            }
        }
        batchRowRepository.deleteBatch(batchId);
    }

    @Override
    @Transactional(readOnly = true)
    public MigrationBatchSummaryResponse summarize(String batchId) {
        return new MigrationBatchSummaryResponse(batchRowRepository.countRows(batchId));
    }
}
