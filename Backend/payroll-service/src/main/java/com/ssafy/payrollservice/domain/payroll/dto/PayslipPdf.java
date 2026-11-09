package com.ssafy.payrollservice.domain.payroll.dto;

/** 급여명세서 PDF 파일 */
public record PayslipPdf(
        String fileName,        // 다운로드 파일명 (payslip-2026-10.pdf)
        byte[] content          // PDF 내용
) {

    public static PayslipPdf of(PayslipDetailResponse payslip, byte[] content) {
        return new PayslipPdf(
                String.format("payslip-%d-%02d.pdf", payslip.payYear(), payslip.payMonth()), content);
    }
}
