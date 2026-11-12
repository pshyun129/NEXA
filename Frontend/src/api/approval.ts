import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import { usePollWindow } from '@/common/usePollWindow';

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

export function useDocument(id: number | null, pollPending = false) {
  const poll = usePollWindow(pollPending);
  return useQuery({
    queryKey: docKeys.detail(id ?? 0),
    queryFn: () => api.get<DocumentDetail>(`${BASE}/${id}`),
    enabled: id != null,
    // 다른 문서로 넘어갈 때 이전 문서를 잠깐 보여 주지 않는다(잘못된 문서에 승인하는 사고 방지)
    placeholderData: undefined,
    refetchInterval: (q) => (poll.active && q.state.data?.status === 'INPROGRESS' && !document.hidden ? 5_000 : false),
    refetchIntervalInBackground: false,
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

/* ---------- 기안·재기안·참조자(P1, 요구사항 3.4) ---------- */

/** 화면에서 직접 기안할 수 있는 양식은 이 둘뿐(나머지는 휴가·근태·자동화가 만든다) */
export type DraftFormType = 'GENERAL' | 'BUSINESS_TRIP';

/** 기본 결재선 미리보기(저장 안 함). selfApproval이면 결재자 없이 상신 즉시 승인(대표이사 기안) */
export interface ApprovalLinePreview {
  formType: FormType;
  approvalLevel: 'TEAM' | 'DIVISION' | 'COMPANY';
  selfApproval: boolean;
  approvalLines: { empId: number; step: number; role: 'APPROVER' | 'AGREEMENT' }[];
}

/**
 * 결재선 미리보기(GET /documents/approval-line-preview). 전결 기준 금액은 프론트가 하드코딩하지 않고
 * 이 응답의 결재선만 보여 준다. attributes는 쿼리로 보낸다(값이 없으면 키를 빼서 "조건 미충족"으로 둔다)
 */
export function useApprovalLinePreview(formType: DraftFormType, attributes: ApprovalAttributes) {
  const params = Object.fromEntries(Object.entries({ formType, ...attributes }).filter(([, v]) => v != null));
  return useQuery({
    queryKey: ['documents', 'line-preview', params],
    queryFn: () => api.get<ApprovalLinePreview>(`${BASE}/approval-line-preview`, { params }),
    placeholderData: (prev) => prev,
  });
}

/** 직접 기안 + 상신(POST /documents). 결재선은 서버가 규정·조직도로 만든다. 참조자는 상신 후 따로 넣는다 */
export function useCreateDocument() {
  const qc = useQueryClient();
  return useMutation({
    // requestKey: 같은 키로 다시 보내면 서버가 처음 만든 문서를 돌려준다(연타·재시도 중복 상신 방지)
    mutationFn: (body: { title: string; formType: DraftFormType; attributes: ApprovalAttributes; requestKey: string }) =>
      api.post<{ documentId: number; docNo: string }>(BASE, body),
    onSettled: () => qc.invalidateQueries({ queryKey: docKeys.lists }),
  });
}

/** 재기안(반려된 직접 기안만, 차수 +1, 결재선은 재기안 시점 조직도로 다시 만든다) */
export function useResubmit(id: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (title: string) => api.post<null>(`${BASE}/${id}/resubmit`, { title }),
    onSettled: () => {
      qc.invalidateQueries({ queryKey: docKeys.detail(id) });
      qc.invalidateQueries({ queryKey: docKeys.lists });
    },
  });
}

/** 참조자 추가·제거(기안자, 진행 중). 규칙 위반은 서버가 APPROVAL_0xx로 알려 준다 → 메시지 그대로 */
export function useReferrer(id: number) {
  const qc = useQueryClient();
  const refresh = () => qc.invalidateQueries({ queryKey: docKeys.detail(id) });
  const add = useMutation({ mutationFn: (empId: number) => api.post<null>(`${BASE}/${id}/referrers`, { empId }), onSettled: refresh });
  const remove = useMutation({ mutationFn: (empId: number) => api.delete<null>(`${BASE}/${id}/referrers/${empId}`), onSettled: refresh });
  return { add, remove };
}
