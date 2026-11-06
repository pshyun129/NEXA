package com.ssafy.payrollservice.domain.payroll.pdf;

import com.ssafy.payrollservice.domain.payroll.dto.PayslipDetailResponse;
import com.ssafy.payrollservice.global.exception.BusinessException;
import com.ssafy.payrollservice.global.exception.ErrorCode;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;

/**
 * 급여명세서 PDF 생성 (상세 응답 → A4 한 장).
 * PDF 기본 폰트에는 한글이 없어 나눔고딕(resources/fonts)을 문서 안에 넣어 그린다.
 */
@Component
public class PayslipPdfRenderer {

    private static final String REGULAR_FONT = "NanumGothic-Regular.ttf";
    private static final String BOLD_FONT = "NanumGothic-Bold.ttf";
    private static final Color HEADER_BACKGROUND = new Color(0xF2, 0xF2, 0xF2);
    private static final Color BORDER = new Color(0xBF, 0xBF, 0xBF);

    // 폰트 파일은 시작할 때 한 번만 읽는다
    private final byte[] regularFont = readFont(REGULAR_FONT);
    private final byte[] boldFont = readFont(BOLD_FONT);

    /** 문서마다 새로 만드는 폰트 (PDF에는 그 문서에 쓴 글자만 들어가므로 문서 사이에 공유하지 않는다) */
    private record Fonts(Font title, Font header, Font body, Font emphasis) {
    }

    public byte[] render(PayslipDetailResponse payslip) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Fonts fonts = createFonts();
            Document document = new Document(PageSize.A4, 40, 40, 50, 40);
            PdfWriter.getInstance(document, out);
            document.open();
            document.add(title(payslip, fonts));
            document.add(payDate(payslip, fonts));
            document.add(employeeTable(payslip, fonts));
            document.add(workTimeTable(payslip.workTime(), fonts));
            document.add(itemTable(payslip, fonts));
            document.add(netPayTable(payslip, fonts));
            document.close();
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            throw new BusinessException(ErrorCode.PAYSLIP_PDF_FAILED, "급여명세서 PDF 생성 실패: " + e.getMessage());
        }
    }

    private Fonts createFonts() throws IOException {
        BaseFont regular = BaseFont.createFont(REGULAR_FONT, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, false, regularFont, null);
        BaseFont bold = BaseFont.createFont(BOLD_FONT, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, false, boldFont, null);
        return new Fonts(new Font(bold, 16), new Font(bold, 9), new Font(regular, 9), new Font(bold, 11));
    }

    /** 제목 (2026년 10월 급여명세서) */
    private Paragraph title(PayslipDetailResponse payslip, Fonts fonts) {
        Paragraph title = new Paragraph(
                payslip.payYear() + "년 " + payslip.payMonth() + "월 급여명세서", fonts.title());
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(4);
        return title;
    }

    private Paragraph payDate(PayslipDetailResponse payslip, Fonts fonts) {
        Paragraph payDate = new Paragraph("지급일 " + date(payslip.payDate()), fonts.body());
        payDate.setAlignment(Element.ALIGN_RIGHT);
        payDate.setSpacingAfter(12);
        return payDate;
    }

    /** 사원 정보 (생성 시점 스냅샷) */
    private PdfPTable employeeTable(PayslipDetailResponse payslip, Fonts fonts) {
        PayslipDetailResponse.Employee employee = payslip.employee();
        PdfPTable table = table(new float[]{1, 2, 1, 2});
        table.addCell(header("성명", fonts));
        table.addCell(body(employee.empName(), fonts, Element.ALIGN_LEFT));
        table.addCell(header("부서", fonts));
        table.addCell(body(employee.deptName(), fonts, Element.ALIGN_LEFT));
        table.addCell(header("직급", fonts));
        table.addCell(body(employee.positionName(), fonts, Element.ALIGN_LEFT));
        table.addCell(header("입사일", fonts));
        table.addCell(body(date(employee.hireDate()), fonts, Element.ALIGN_LEFT));
        return table;
    }

    /** 근태 집계 (생성 시점 스냅샷) */
    private PdfPTable workTimeTable(PayslipDetailResponse.WorkTime workTime, Fonts fonts) {
        PdfPTable table = table(new float[]{1, 1, 1, 1, 1, 1, 1});
        List.of("근무일수", "총 근로", "기본 근로", "연장 근로", "야간 근로", "휴일 근로", "휴가")
                .forEach(name -> table.addCell(header(name, fonts)));
        table.addCell(body(workTime.workDays() + "일", fonts, Element.ALIGN_CENTER));
        List.of(workTime.totalMin(), workTime.regularMin(), workTime.otExtendedMin(),
                        workTime.otNightMin(), workTime.otHolidayMin(), workTime.leaveMin())
                .forEach(min -> table.addCell(body(duration(min), fonts, Element.ALIGN_CENTER)));
        return table;
    }

    /** 지급·공제 항목을 나란히 (0원 포함) + 합계 */
    private PdfPTable itemTable(PayslipDetailResponse payslip, Fonts fonts) {
        PdfPTable table = table(new float[]{2, 1.5f, 2, 1.5f});
        table.addCell(header("지급 항목", fonts));
        table.addCell(header("금액", fonts));
        table.addCell(header("공제 항목", fonts));
        table.addCell(header("금액", fonts));

        List<PayslipDetailResponse.Item> earnings = payslip.earnings();
        List<PayslipDetailResponse.Item> deductions = payslip.deductions();
        for (int i = 0; i < Math.max(earnings.size(), deductions.size()); i++) {
            addItem(table, i < earnings.size() ? earnings.get(i) : null, fonts);
            addItem(table, i < deductions.size() ? deductions.get(i) : null, fonts);
        }

        table.addCell(header("지급합계", fonts));
        table.addCell(amountCell(payslip.totalPay(), fonts.header()));
        table.addCell(header("공제합계", fonts));
        table.addCell(amountCell(payslip.totalDeduction(), fonts.header()));
        return table;
    }

    /** 항목 이름과 금액. 한쪽 항목이 더 적으면 빈 칸 */
    private void addItem(PdfPTable table, PayslipDetailResponse.Item item, Fonts fonts) {
        table.addCell(body(item == null ? "" : item.name(), fonts, Element.ALIGN_LEFT));
        table.addCell(item == null ? body("", fonts, Element.ALIGN_RIGHT) : amountCell(item.amount(), fonts.body()));
    }

    /** 실지급액 */
    private PdfPTable netPayTable(PayslipDetailResponse payslip, Fonts fonts) {
        PdfPTable table = table(new float[]{5.5f, 1.5f});
        PdfPCell label = cell("실지급액", fonts.emphasis(), Element.ALIGN_CENTER);
        label.setBackgroundColor(HEADER_BACKGROUND);
        table.addCell(label);
        table.addCell(amountCell(payslip.netPay(), fonts.emphasis()));
        return table;
    }

    private static PdfPTable table(float[] widths) {
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        table.setSpacingAfter(12);
        return table;
    }

    private static PdfPCell header(String text, Fonts fonts) {
        PdfPCell cell = cell(text, fonts.header(), Element.ALIGN_CENTER);
        cell.setBackgroundColor(HEADER_BACKGROUND);
        return cell;
    }

    private static PdfPCell body(String text, Fonts fonts, int alignment) {
        return cell(text, fonts.body(), alignment);
    }

    private static PdfPCell amountCell(int amount, Font font) {
        return cell(String.format("%,d", amount), font, Element.ALIGN_RIGHT);
    }

    private static PdfPCell cell(String text, Font font, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text == null ? "" : text, font));
        cell.setHorizontalAlignment(alignment);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(5);
        cell.setBorderColor(BORDER);
        return cell;
    }

    /** 분 → 160시간 30분 */
    private static String duration(int minutes) {
        return minutes / 60 + "시간 " + minutes % 60 + "분";
    }

    private static String date(LocalDate date) {
        return date == null ? "-" : date.toString();
    }

    private static byte[] readFont(String fileName) {
        try (InputStream in = new ClassPathResource("fonts/" + fileName).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("폰트 파일을 읽을 수 없습니다: " + fileName, e);
        }
    }
}
