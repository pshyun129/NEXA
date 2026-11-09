import { keepPreviousData, useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import { kst } from '@/format';

// leave-service 사원용 API(P1, 설계 6.3). 2026-11-09 develop(2a6248c) 기준.
// 수량은 모두 분 단위(하루 480분). 관리자 휴가 화면은 P2라 두지 않는다.

export type LeavePortion = 'FULL' | 'AM_HALF' | 'PM_HALF';
export type LeaveRequestStatus = 'DRAFT' | 'SUBMITTING' | 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN' | 'CANCELLED';
export type AllowedUnits = 'FULL' | 'HALF' | 'HOUR' | 'MIXED';
export type GrantKind = 'AUTO' | 'MANUAL' | 'CARRY_OVER' | 'COMP';
export type LedgerEventType =
  | 'GRANT' | 'ADJUST' | 'RESERVE' | 'RELEASE' | 'CONSUME' | 'RESTORE' | 'EXPIRE' | 'REVOKE' | 'CARRY_OUT' | 'CARRY_IN';

/** quantity는 예약분을 포함한 장부 잔액, remaining은 예약을 뺀 사용 가능 잔액(조회 시점 유효분만) */
export interface LeaveBalance {
  accountId: number;
  balanceTypeId: number;
  balanceTypeName: string;
  quantityMinutes: number;
  reservedMinutes: number;
  remainingMinutes: number;
}

export interface LeaveGrant {
  grantId: number;
  balanceTypeName: string;
  grantKind: GrantKind;
  grantedOn: string | null;
  originalMinutes: number;
  validFrom: string;
  expiresOn: string | null;
  createdAt: string;
}

export interface LeaveLedgerEntry {
  entryId: number;
  balanceTypeName: string;
  grantId: number;
  leaveReqId: number | null;
  eventType: LedgerEventType;
  quantityDelta: number;
  reservedDelta: number;
  reason: string | null;
  createdAt: string;
}

export interface LeaveType {
  leaveTypeId: number;
  leaveTypeName: string;
  allowedUnits: AllowedUnits;
  effectiveMinMinutes: number | null;
  effectiveMaxMinutes: number | null;
}

export interface LeaveDateInput { leaveDate: string; portion: LeavePortion }
export interface LeaveInput { leaveTypeId: number; reason: string | null; dates: LeaveDateInput[] }

export interface LeavePreview {
  requestable: boolean;
  validationStatus: 'REQUESTABLE' | 'NOT_REQUESTABLE' | 'UNVERIFIABLE';
  holidayVerification: 'VERIFIED' | 'UNAVAILABLE';
  totalRequestedMinutes: number;
  balanceDeductionMinutes: number;
  balanceRequired: boolean;
  /** 신청 전 사용 가능 잔액 */
  remainingMinutes: number | null;
  dates: { leaveDate: string; portion: LeavePortion; requestedMinutes: number; balanceDeductionMinutes: number }[];
  reasons: { code: string; date: string | null; message: string }[];
}

export interface LeaveSubmission {
  requestId: number;
  status: LeaveRequestStatus;
  approvalCreationStatus: 'NOT_SENT' | 'IN_PROGRESS' | 'UNKNOWN' | 'LINKED' | 'FAILED';
  approvalDocId: number | null;
  message: string;
}

export interface LeaveRequestSummary {
  requestId: number;
  leaveTypeId: number;
  leaveTypeName: string;
  status: LeaveRequestStatus;
  totalMinutes: number;
  totalDeductMinutes: number;
  submittedAt: string | null;
  createdAt: string;
}

export interface LeaveRequestDetail extends LeaveRequestSummary {
  approvalDocId: number | null;
  reason: string | null;
  cancelledAt: string | null;
  cancelReason: string | null;
  /** leaveDate는 서울 자정을 UTC로 준다(2026-11-16 → 2026-11-15T15:00:00Z). 화면에서는 leaveDay()로 바꾼다 */
  dates: { requestDateId: number; leaveDate: string; portion: LeavePortion | 'HOURLY'; requestedMinutes: number; deductMinutes: number }[];
}

const ME = '/api/v1/leaves/me';

export const leaveKeys = {
  all: ['leave'] as const,
  summary: ['leave', 'summary'] as const,
  grants: ['leave', 'grants'] as const,
  ledger: ['leave', 'ledger'] as const,
  types: ['leave', 'types'] as const,
  requests: ['leave', 'requests'] as const,
  request: (id: number) => ['leave', 'request', id] as const,
  preview: (input: LeaveInput | null) => ['leave', 'preview', input] as const,
};

/** 잔액 요약. 정책이 배정되지 않았으면 서버가 빈 목록을 준다(신청 유형 조회에서 LEAVE_010) */
export function useLeaveSummary() {
  return useQuery({
    queryKey: leaveKeys.summary,
    queryFn: () => api.get<{ asOf: string; balances: LeaveBalance[] }>(`${ME}/summary`),
  });
}

export function useLeaveGrants() {
  return useQuery({ queryKey: leaveKeys.grants, queryFn: () => api.get<LeaveGrant[]>(`${ME}/grants`) });
}

export function useLeaveLedger() {
  return useQuery({ queryKey: leaveKeys.ledger, queryFn: () => api.get<LeaveLedgerEntry[]>(`${ME}/ledger`) });
}

/** 신청할 수 있는 유형. 정책 배정이 없으면 404 LEAVE_010 */
export function useLeaveTypes() {
  return useQuery({
    queryKey: leaveKeys.types,
    queryFn: () => api.get<LeaveType[]>('/api/v1/leaves/types'),
    retry: false,
    staleTime: 5 * 60_000,
  });
}

/** 처리 확인 중(SUBMITTING) 신청이 있을 때만 5초마다 다시 읽는다. 결재 대기(SUBMITTED)는 사람을 기다리므로 폴링하지 않는다 */
export function useMyLeaveRequests() {
  return useQuery({
    queryKey: leaveKeys.requests,
    queryFn: () => api.get<LeaveRequestSummary[]>(`${ME}/requests`),
    refetchInterval: (q) => (q.state.data?.some((r) => r.status === 'SUBMITTING') && !document.hidden ? 5_000 : false),
  });
}

export function useLeaveRequest(id: number | null) {
  return useQuery({
    queryKey: leaveKeys.request(id ?? 0),
    queryFn: () => api.get<LeaveRequestDetail>(`${ME}/requests/${id}`),
    enabled: id != null,
    placeholderData: undefined,
  });
}

/** 목록 응답에 날짜가 없어 상세를 따로 읽는다(설계 9장 #11). 보이는 행만 넘긴다 */
export function useLeaveRequestDetails(ids: number[]) {
  return useQueries({
    queries: ids.map((id) => ({
      queryKey: leaveKeys.request(id),
      queryFn: () => api.get<LeaveRequestDetail>(`${ME}/requests/${id}`),
      staleTime: 30_000,
    })),
  });
}

/** 저장하지 않는 미리보기. 입력이 같으면 캐시를 쓰고, [다시 확인]은 refetch */
export function useLeavePreview(input: LeaveInput | null) {
  return useQuery({
    queryKey: leaveKeys.preview(input),
    queryFn: () => api.post<LeavePreview>(`${ME}/requests/preview`, input),
    enabled: input != null && input.dates.length > 0,
    placeholderData: keepPreviousData,
    retry: false,
    staleTime: 0,
    gcTime: 60_000,
  });
}

/**
 * 제출. 같은 Idempotency-Key로 다시 보내면 서버가 기존 결과를 돌려준다(201 제출, 202 처리 확인 중, 200 기존 결과).
 * 상태는 본문 status로 구분한다.
 */
export function useSubmitLeave() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ key, input }: { key: string; input: LeaveInput }) =>
      api.post<LeaveSubmission>(`${ME}/requests`, input, { headers: { 'Idempotency-Key': key } }),
    onSuccess: (res) => {
      qc.invalidateQueries({ queryKey: leaveKeys.all });
      if (res.approvalDocId != null) qc.invalidateQueries({ queryKey: ['documents'] });
    },
  });
}

/** 상세의 leaveDate(서울 자정의 UTC 표현)를 서울 날짜 문자열로 */
export const leaveDay = (v: string) => kst(v).format('YYYY-MM-DD');
