package com.ssafy.payrollservice.domain.payroll.pdf;

import com.ssafy.payrollservice.domain.payroll.dto.PayslipDetailResponse;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 명세서 상세 → PDF: 한 장짜리 PDF가 만들어지고 금액이 들어 있는지 (Spring 없이) */
class PayslipPdfRendererTest {

    private final PayslipPdfRenderer renderer = new PayslipPdfRenderer();

    private static PayslipDetailResponse payslip() {
        return new PayslipDetailResponse(
                1L, 2026, 10, LocalDate.of(2026, 11, 10),
                new PayslipDetailResponse.Employee("홍길동", "개발팀", "사원", LocalDate.of(2024, 1, 2)),
                new PayslipDetailResponse.WorkTime(21, 10_200, 9_600, 480, 60, 0, 480),
                List.of(new PayslipDetailResponse.Item("BASE_PAY", "기본급", 3_500_000),
                        new PayslipDetailResponse.Item("OVERTIME_PAY", "연장근로수당", 150_000),
                        new PayslipDetailResponse.Item("BONUS", "상여금", 0)),
                List.of(new PayslipDetailResponse.Item("NATIONAL_PENSION", "국민연금", 164_250),
                        new PayslipDetailResponse.Item("INCOME_TAX", "소득세", 98_760)),
                3_650_000, 263_010, 3_386_990);
    }

    @Test
    void 한_장짜리_PDF를_만듦() throws IOException {
        byte[] pdf = renderer.render(payslip());

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        try (PdfReader reader = new PdfReader(pdf)) {
            assertThat(reader.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void 금액과_합계가_들어_있음() throws IOException {
        byte[] pdf = renderer.render(payslip());

        try (PdfReader reader = new PdfReader(pdf)) {
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(text).contains("3,500,000", "3,650,000", "263,010", "3,386,990", "2026-11-10");
        }
    }

    @Test
    void 지급일이_없어도_만들어짐() {
        PayslipDetailResponse base = payslip();
        PayslipDetailResponse noPayDate = new PayslipDetailResponse(
                base.payslipId(), base.payYear(), base.payMonth(), null, base.employee(), base.workTime(),
                base.earnings(), List.of(), base.totalPay(), 0, base.totalPay());

        assertThat(renderer.render(noPayDate)).isNotEmpty();
    }
}
