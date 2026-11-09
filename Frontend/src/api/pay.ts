import { useMutation, useQuery } from '@tanstack/react-query';
import { api } from './client';

// payroll-service 본인 급여(PayslipController, /api/v1/pay/me, origin/develop def2081 기준).
// 명세서는 생성 즉시 조회된다(확정·발송 개념 없음). 금액은 원 단위 정수.

const BASE = '/api/v1/pay/me';

export interface PayslipListItem { payslipId: number; payMonth: number; payDate: string; totalPay: number; totalDeduction: number; netPay: number }
export interface PayslipList {
  year: number;
  /** 최근 월부터 */
  payslips: PayslipListItem[];
  total: { totalPay: number; totalDeduction: number; netPay: number };
}

export interface PayItem { itemCode: string; name: string; amount: number }
export interface PayslipDetail {
  payslipId: number; payYear: number; payMonth: number; payDate: string;
  /** 생성 시점 스냅샷. positionName은 직급명(이관한 지난 명세서는 원본 직위명) */
  employee: { empName: string; deptName: string; positionName: string | null; hireDate: string };
  workTime: { workDays: number; totalMin: number; regularMin: number; otExtendedMin: number; otNightMin: number; otHolidayMin: number; leaveMin: number };
  /** 0원 항목 포함 */
  earnings: PayItem[];
  deductions: PayItem[];
  totalPay: number; totalDeduction: number; netPay: number;
}

/** 토큰 gradeId 기준 직급 기본급(개인 연봉 계약이 아님) */
export interface Contract { gradeId: number; monthlyBasePay: number; annualSalary: number }

export interface RetirementEstimate {
  hireDate: string; resignDate: string; settlementStart: string; settlementEnd: string; serviceDays: number;
  periodStart: string; periodEnd: string; periodDays: number; threeMonthPay: number;
  /** 3 미만이면 월평균 × 3으로 환산 */
  basePayslipCount: number;
  annualBonus: number; annualLeavePay: number; dailyAverageWage: number; retirementPay: number;
}

export function usePayslips(year: number) {
  return useQuery({ queryKey: ['pay', 'payslips', year], queryFn: () => api.get<PayslipList>(`${BASE}/payslips`, { params: { year } }) });
}

export function usePayslip(id: number | null) {
  return useQuery({
    queryKey: ['pay', 'payslip', id],
    queryFn: () => api.get<PayslipDetail>(`${BASE}/payslips/${id}`),
    enabled: id != null,
  });
}

/** PDF는 쿠키 인증 같은 origin이라 링크로 바로 내려받는다(Content-Disposition attachment) */
export const payslipPdfUrl = (id: number) => `${BASE}/payslips/${id}/pdf`;

/** PAY_002: 토큰에 직급이 없거나 직급 기본급 미등록 → 서버 메시지 그대로 */
export function useContract() {
  return useQuery({ queryKey: ['pay', 'contract'], queryFn: () => api.get<Contract>(`${BASE}/contract`), retry: false });
}

/** 관리자 명세서 생성 결과(AdminPayslipController, ADMIN) */
export interface PayslipGenerated {
  payslipId: number; empId: number; payYear: number; payMonth: number; payDate: string;
  totalPay: number; totalDeduction: number; netPay: number;
}

/**
 * 사원 1명의 월 명세서 생성(month = YYYY-MM). 같은 달이 이미 있으면 409 PAY_003,
 * 직급·기본급이 없으면 404 PAY_002, user-service·근태 호출 실패는 502 PAY_009·PAY_001
 */
export function useGeneratePayslip() {
  return useMutation({
    mutationFn: ({ empId, month }: { empId: number; month: string }) =>
      api.post<PayslipGenerated>('/api/v1/pay/admin/payslips/generate', { empId, month }),
  });
}

/** resignDate = 퇴직일(마지막 근무일 다음 날). 명세서가 없으면 404 PAY_007 */
export function useRetirementEstimate(resignDate: string | null) {
  return useQuery({
    queryKey: ['pay', 'retirement', resignDate],
    queryFn: () => api.get<RetirementEstimate>(`${BASE}/retirement-estimate`, { params: { resignDate } }),
    enabled: !!resignDate,
    retry: false,
  });
}
