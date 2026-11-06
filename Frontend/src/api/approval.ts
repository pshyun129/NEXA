import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';

// approval-service DTO (2026-11-05 develop 기준)

export type FormType = 'LEAVE' | 'OVERTIME' | 'TIME_FIX' | 'HOLIDAY_SUBST' | 'BUSINESS_TRIP' | 'GENERAL';
export type DocumentStatus = 'INPROGRESS' | 'APPROVED' | 'REJECTED' | 'CANCEL';
export type SourceType = 'DIRECT' | 'LEAVE' | 'ATTENDANCE' | 'AUTOMATION';
export type LineRole = 'APPROVER' | 'AGREEMENT' | 'REFERENCE' | 'VIEWER';
export type LineAction = 'PENDING' | 'AGREE' | 'DISAGREE' | 'SKIPPED';
export type HistoryAction =
  | 'SUBMIT' | 'APPROVE' | 'REJECT' | 'FINAL_APPROVE' | 'CANCEL' | 'RESUBMIT' | 'APPROVER_ADD' | 'APPROVER_REMOVE';

/** DocumentSummaryResponse. 진행 상황은 서버 ApprovalProgress 규칙(현재 차례 = 첫 PENDING 결재·합의 줄) */
export interface DocumentSummary {
  documentId: number;
  docNo: string;
  formType: FormType;
  title: string;
  status: DocumentStatus;
  drafterId: number;
  draftedAt: string;
  completedAt: string | null;
  sourceType: SourceType;
  sourceId: number | null;
  /** 출처 표시 이름(상신 시점 스냅샷, 예: 워크플로 이름). 직접 기안·이전 문서·보내지 않은 출처는 null */
  sourceLabel: string | null;
  /** 현재 차례 사원. 진행중이 아니면 [] (순차 결재라 지금은 최대 1명) */
  currentApproverIds: number[];
  /** 현재 차례가 결재자 중 몇 번째(1부터). 진행중이 아니면 null */
  currentStep: number | null;
  /** 결재·합의 줄 수(전결 생략 포함) */
  totalSteps: number;
  myTurn: boolean;
}

export interface ApprovalLineItem {
  lineId: number;
  step: number;
  role: LineRole;
  action: LineAction;
  empId: number;
  actedAt: string | null;
  required: boolean;
}

export interface ApprovalHistoryItem {
  historyId: number;
  round: number;
  actorId: number;
  action: HistoryAction;
  targetEmpId: number | null;
  comment: string | null;
  occurredAt: string;
}

/** ApprovalAttributes: 전결 판단에 쓴 신청 값(상신 시점 스냅샷) */
export interface ApprovalAttributes {
  days?: number;
  overtimeMinutes?: number;
  monthlyOvertimeMinutes?: number;
  overseas?: boolean;
  amount?: number;
}

/** DocumentInfoResponse */
export interface DocumentDetail {
  documentId: number;
  docNo: string;
  formType: FormType;
  title: string;
  status: DocumentStatus;
  drafterId: number;
  draftedAt: string;
  completedAt: string | null;
  sourceType: SourceType;
  sourceId: number | null;
  /** 출처 표시 이름. 없으면 null(sourceType 기본 문구로 대신 표시) */
  sourceLabel: string | null;
  requestKey: string | null;
  approvalLevel: 'TEAM' | 'DIVISION' | 'COMPANY' | null;
  attributes: ApprovalAttributes | null;
  approvalLines: ApprovalLineItem[];
  histories: ApprovalHistoryItem[];
}

const BASE = '/api/v1/documents';

export interface DocumentFilter {
  /** DRAFTED 기안함, PENDING 결재 대기함(내 차례, 오래된 순). 없으면 볼 수 있는 전체 */
  box?: 'DRAFTED' | 'PENDING';
  approvalStatus?: DocumentStatus;
  formType?: FormType;
}

export const docKeys = {
  // 목록·건수는 모두 'documents' 아래에 둬서 결재 처리 후 한 번에 다시 불러온다
  lists: ['documents'] as const,
  list: (f: DocumentFilter) => ['documents', 'list', f] as const,
  count: (f: DocumentFilter) => ['documents', 'count', f] as const,
  detail: (id: number) => ['document', id] as const,
};

export function useDocuments(filter: DocumentFilter) {
  return useQuery({
    queryKey: docKeys.list(filter),
    queryFn: () => api.get<{ documents: DocumentSummary[]; totalCount: number }>(BASE, { params: filter }),
    select: (d) => d.documents,
  });
}

/** 결재 대기함(box=PENDING). 홈 위젯과 결재함이 같이 쓴다. 30초 + 포커스 재조회(설계 7.3) */
export function usePendingDocuments(enabled = true) {
  return useQuery({
    queryKey: docKeys.list({ box: 'PENDING' }),
    queryFn: () => api.get<{ documents: DocumentSummary[]; totalCount: number }>(BASE, { params: { box: 'PENDING' } }),
    select: (d) => d.documents,
    enabled,
    refetchInterval: () => (document.hidden ? false : 30_000),
    refetchIntervalInBackground: false,
  });
}

/** 건수(GET /documents/count). 탑바·사이드바 카운터용: 60초 + 포커스, 숨겨진 탭은 중단 */
export function useDocumentCount(filter: DocumentFilter, enabled = true) {
  return useQuery({
    queryKey: docKeys.count(filter),
    queryFn: () => api.get<{ count: number }>(`${BASE}/count`, { params: filter }),
    select: (d) => d.count,
    enabled,
    refetchInterval: () => (document.hidden ? false : 60_000),
    refetchIntervalInBackground: false,
    retry: false,
  });
}

export function useDocument(id: number | null) {
  return useQuery({
    queryKey: docKeys.detail(id ?? 0),
    queryFn: () => api.get<DocumentDetail>(`${BASE}/${id}`),
    enabled: id != null,
    // 다른 문서로 넘어갈 때 이전 문서를 잠깐 보여 주지 않는다(잘못된 문서에 승인하는 사고 방지)
    placeholderData: undefined,
  });
}

export type DocumentAction =
  | { kind: 'approve' }
  | { kind: 'reject'; comment: string }
  | { kind: 'final-approve' }
  | { kind: 'cancel' };

export function useDocumentAction(id: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (a: DocumentAction) =>
      api.post<null>(`${BASE}/${id}/${a.kind}`, a.kind === 'reject' ? { comment: a.comment } : undefined),
    // 낙관적 갱신 없이 서버 상태를 다시 읽는다(설계 7.3)
    onSettled: () => {
      qc.invalidateQueries({ queryKey: docKeys.detail(id) });
      qc.invalidateQueries({ queryKey: docKeys.lists });
    },
  });
}
