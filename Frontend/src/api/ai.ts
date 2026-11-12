import { keepPreviousData, useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import type { Graph } from './builder';

// FastAPI ai-service (origin/develop f4725fd 기준). 바깥 필드는 camelCase, inputs·outputs·step output 안쪽은 snake_case.
// 시각은 오프셋 없는 UTC라 화면에서는 serverUtc()를 거쳐 쓴다(frontend-backend-gaps.md B13).

const BASE = '/api/v1/ai';

export type RunStatus = 'QUEUED' | 'RUNNING' | 'WAITING' | 'SUCCEEDED' | 'FAILED' | 'CANCELED';
export type StepStatus = 'RUNNING' | 'WAITING' | 'SUCCEEDED' | 'FAILED';

/** 실행 진입 화면 코드(요구사항 3.7). 빌더 placements와 같은 값. 시연은 HOME만 쓴다 */
export const SCREEN_CODES = ['HOME', 'ATTENDANCE', 'LEAVE', 'APPROVAL_DRAFT', 'PAYROLL'] as const;
export type ScreenCode = (typeof SCREEN_CODES)[number];

export interface WorkflowSummary {
  id: number;
  name: string;
  description: string | null;
  deployedVersion: number | null;
  createdBy: string | null;
  updatedAt: string;
}

export interface FormField {
  key: string;
  label: string;
  type: 'text' | 'number' | 'date' | 'select' | 'file' | 'receipt_facts';   // receipt_facts: 영수증별 정보 표(서버가 자동으로 넣음)
  required: boolean;
  options: string[];
  prefill: string | null;
}

export interface WorkflowDetail extends WorkflowSummary {
  inputForm: FormField[];
  placements: string[];
  createdAt: string;
}

export interface RunSummary {
  id: number;
  workflowId: number;
  version: number | null;
  isTest: boolean;
  status: RunStatus;
  currentNodeId: string | null;
  /** 토큰 sub 문자열("103") */
  requestedBy: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  /** WAITING일 때 무엇을 기다리는지(runs/models.py Run.waiting_type). 사람 확인 / 결재 대기 */
  waitingType?: 'human_confirm' | 'approval_wait' | null;
}

/** 목록 배지 값: 결재 대기는 "확인 대기"와 구분한다(QA C20). 나머지는 실행 상태 그대로 */
export const runBadgeValue = (r: { status: RunStatus; waitingType?: RunSummary['waitingType'] }) =>
  r.status === 'WAITING' && r.waitingType === 'approval_wait' ? 'WAITING_APPROVAL' : r.status;

/**
 * 반려로 끝났는지(QA AUTO-B3). 반려돼도 실행은 SUCCEEDED로 끝나고(요구사항 3.7) 마지막 단계는 업무 완료라
 * 마지막 단계만 보면 놓친다 → 결재 대기·사람 확인 단계 중 '반려/회수' 결과가 있었는지 본다
 */
export const runEndedRejected = (status: RunStatus | undefined, steps: { branch: string | null }[] | undefined) =>
  status === 'SUCCEEDED' && !!steps?.some((st) => st.branch === '반려' || st.branch === '회수');

/** 실행에 붙은 파일. 서버가 inputs.files에 넣는다(runs/service.py `_file_info`, snake_case). path는 서버 경로라 쓰지 않는다 */
export interface RunInputFile { file_id: number; name: string; content_type: string | null; size: number }

export function runInputFiles(run: RunDetail): RunInputFile[] {
  const files = run.inputs?.files;
  return Array.isArray(files) ? (files as RunInputFile[]) : [];
}

/**
 * 원본 내려받기(GET /runs/files/{id}/download, origin/develop e712f8e). 쿠키 인증이라 <img src>·링크로 바로 쓴다.
 * 업로드한 사람·실행 요청자, 그리고 그 실행이 올린 결재 문서의 결재자(documentId를 같이 보냄, 읽기 전용)가 받을 수 있다.
 */
export const runFileDownloadUrl = (fileId: number, documentId?: number) =>
  `${BASE}/runs/files/${fileId}/download${documentId ? `?document_id=${documentId}` : ''}`;

export interface RunDetail extends RunSummary {
  inputs: Record<string, unknown>;
  outputs: Record<string, unknown> | null;
  error: string | null;
  /** 실행 당시 그래프(runs/schemas.py RunDetail.graph). 예전 서버면 없어서 화면은 워크플로 초안으로 대신 그린다 */
  graph?: Graph;
}

export interface RunStep {
  id: number;
  nodeId: string;
  nodeType: string;
  status: StepStatus;
  /** 나간 분기 라벨(카탈로그 branches 문자열: 성공/확인 필요/자동 확정/사람 확인/확인/반려/승인/회수 …) */
  branch: string | null;
  output: Record<string, unknown> | null;
  error: string | null;
  startedAt: string;
  finishedAt: string | null;
}

export interface RunFile {
  id: number;
  runId: number | null;
  originalName: string;
  contentType: string | null;
  size: number;
  uploadedBy: string | null;
  createdAt: string;
}

export interface NodeSummary {
  nodeType: string;
  name: string;
  category: 'input' | 'ai' | 'rule' | 'human' | 'output' | string;
  description: string;
}

/** 영수증 한 장의 확인 정보(FastAPI nodes/impl/human_confirm.py compact) */
export interface HitlReceipt {
  index: number;
  file_id: number | null;
  name: string | null;
  fields: Record<string, unknown>;
  /** 사람이 봐야 하는 장(자동 검증에서 걸렸거나 규정 검토가 준수가 아님) */
  flagged: boolean;
  reasons: string[];
  include: boolean;
  note: string | null;
  items: { name: string; quantity: number | null; amount: number | null; kind: string | null }[];
  headcount: { printed: number | null; estimate: { min: number; max: number; basis: string } | null } | null;
  evidence_kind: string | null;
  facts: { purpose?: string; headcount?: number | string; attendees?: string; reason?: string; pay_method?: string; pay_time?: string };
  /** 규정 검토 뒤의 확인이면 판정(준수/위반/확인 필요) */
  verdict: '준수' | '위반' | '확인 필요' | null;
  /** 규정 검토가 더 알아야 하는 사실(규정 용어: 사용 목적·참석 인원·참석자 이름·사용 사유 …) */
  needed_facts: string[];
  gms_fields: Record<string, unknown> | null;
  duplicate_of: number | null;
  /** 다른 실행에서 이미 정산 신청한 영수증(FastAPI runs/duplicates.py): 그 실행·상태·결재 문서 번호 */
  duplicate_prev?: { run_id: number; state: string; doc_no: string | null; approval_status: string | null } | null;
}

/** 사람 확인(human_confirm) 대기 내용 */
export interface HitlWaiting {
  type: 'human_confirm';
  message: string;
  fields: Record<string, unknown>;
  reasons: string[];
  gms_fields: Record<string, unknown> | null;
  /** 영수증 여러 장: 장마다 확인(extraction: 읽은 값 / policy: 규정 검토 결과) */
  mode?: 'extraction' | 'policy';
  receipts?: HitlReceipt[];
}

/** 사람 확인 응답에서 장 하나(바뀐 것만) */
export interface HitlReceiptAnswer {
  index: number;
  data?: Record<string, unknown>;
  facts?: { purpose?: string; headcount?: number; attendees?: string; reason?: string };
  include?: boolean;
  note?: string;
}

/** [다시 검토] 결과 */
export interface PolicyPreview { index: number; verdict: '준수' | '위반' | '확인 필요'; reasons: string[]; needed_facts: string[] }

/** 결재 결과 대기(wait_approval). 수동 재개 불가(RUN_006), Kafka 결과로만 재개 */
export interface ApprovalWaiting {
  type: 'approval_wait';
  message: string;
  request_key: string;
  document_id: number;
  doc_no: string;
  /** 문서 여러 건을 기다릴 때(영수증 여러 장): 문서별 상태 */
  documents?: { document_id: number; doc_no: string; title: string; kind: string; status: string | null }[];
}

export type Waiting = HitlWaiting | ApprovalWaiting;

export const runKeys = {
  run: (id: number) => ['runs', id] as const,
  steps: (id: number) => ['runs', id, 'steps'] as const,
  pending: ['runs', 'me', 'pending'] as const,
};

/** 내 확인 대기(WAITING) 실행. 탑바 카운터와 홈 카드가 같은 키를 쓴다(설계 7.3 전역 카운트: 60초 + 포커스). */
export function useMyPendingRuns(enabled = true) {
  return useQuery({
    queryKey: runKeys.pending,
    queryFn: () => api.get<RunSummary[]>(`${BASE}/runs/me/pending`),
    enabled,
    refetchInterval: () => (document.hidden ? false : 60_000),
    refetchIntervalInBackground: false,
    retry: false,
  });
}

/**
 * 내 실행 이력(P1, 요구사항 3.7 #7). 서버가 최근 50건만 준다(id 내림차순, runs/service.py list_my_runs).
 * status는 값 하나로만 거른다. 진행 중 실행이 있으면 10초마다 다시 불러온다.
 */
export function useMyRuns(status: RunStatus | null, enabled = true) {
  return useQuery({
    queryKey: ['runs', 'me', status ?? 'ALL'] as const,
    queryFn: () => api.get<RunSummary[]>(`${BASE}/runs/me`, { params: status ? { status } : undefined }),
    enabled,
    placeholderData: keepPreviousData,
    refetchInterval: (q) => (!document.hidden && q.state.data?.some((r) => !isFinal(r.status)) ? 10_000 : false),
    refetchIntervalInBackground: false,
  });
}

/** 여러 워크플로 이름. useWorkflow와 같은 키라 캐시를 같이 쓴다. 지워진 워크플로는 이름 없이 둔다 */
export function useWorkflowNames(ids: number[]) {
  const results = useQueries({
    queries: [...new Set(ids)].map((id) => ({
      queryKey: ['workflows', id] as const,
      queryFn: () => api.get<WorkflowDetail>(`${BASE}/workflows/${id}`),
      staleTime: 10 * 60_000,
      retry: false,
    })),
  });
  const names: Record<number, string> = {};
  for (const r of results) if (r.data) names[r.data.id] = r.data.name;
  return names;
}

export function useAvailableWorkflows(screen: ScreenCode) {
  return useQuery({
    queryKey: ['workflows', 'available', screen],
    queryFn: () => api.get<WorkflowSummary[]>(`${BASE}/workflows/available`, { params: { screen } }),
  });
}

export function useWorkflow(id: number | null) {
  return useQuery({
    queryKey: ['workflows', id],
    queryFn: () => api.get<WorkflowDetail>(`${BASE}/workflows/${id}`),
    enabled: id != null,
    staleTime: 10 * 60_000,
  });
}

export function useInputForm(id: number, screen: ScreenCode) {
  return useQuery({
    queryKey: ['workflows', id, 'input-form', screen],
    queryFn: () => api.get<{ workflowId: number; fields: FormField[]; values: Record<string, unknown> }>(
      `${BASE}/workflows/${id}/input-form`, { params: { screen } }),
  });
}

/** 노드 카탈로그: 단계 이름과 AI 노드(category=ai) 표시에 쓴다. 거의 바뀌지 않는다 */
export function useNodeCatalog() {
  return useQuery({
    queryKey: ['nodes'],
    queryFn: () => api.get<NodeSummary[]>(`${BASE}/nodes`),
    staleTime: 60 * 60_000,
    select: (list) => new Map(list.map((n) => [n.nodeType, n])),
  });
}

export function useUploadRunFile() {
  return useMutation({
    mutationFn: (file: File) => {
      const fd = new FormData();
      fd.append('file', file);
      return api.post<RunFile>(`${BASE}/runs/files`, fd, { timeout: 120_000 });
    },
  });
}

export function useDeleteRunFile() {
  return useMutation({ mutationFn: (id: number) => api.delete<null>(`${BASE}/runs/files/${id}`) });
}

export function useStartRun(workflowId: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { inputs: Record<string, unknown>; fileIds: number[] }) =>
      api.post<{ runId: number; status: RunStatus }>(`${BASE}/workflows/${workflowId}/runs`, body),
    // 실행 직후 다른 화면으로 가도 탑바·홈 숫자가 맞도록(QA AUTO-B5)
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: runKeys.pending });
      qc.invalidateQueries({ queryKey: ['runs', 'me'] });
    },
  });
}

const FINAL: RunStatus[] = ['SUCCEEDED', 'FAILED', 'CANCELED'];
export const isFinal = (s: RunStatus | undefined) => !!s && FINAL.includes(s);

/** 폴링 주기(설계 7.3 RunMonitor): QUEUED·RUNNING 1.5초, WAITING 10초, 종료·숨겨진 탭은 중단 */
function pollMs(status: RunStatus | undefined, boost = false) {
  if (document.hidden || !status || isFinal(status)) return false;
  // resume 직후에는 WAITING으로 응답이 와도 곧 RUNNING이 되므로 1.5초로 다시 본다
  return status === 'WAITING' && !boost ? 10_000 : 1_500;
}

/** enabled=false면 부르지 않는다(빌더 캔버스가 테스트 실행이 있을 때만 상태를 겹쳐 그린다) */
/** documentId: 결재자가 결재 문서에서 열 때(읽기 전용, 서버가 결재 서비스로 권한을 확인) */
const docParams = (documentId?: number) => (documentId ? { params: { document_id: documentId } } : undefined);

export function useRun(id: number, boost = false, enabled = true, documentId?: number) {
  const qc = useQueryClient();
  return useQuery({
    queryKey: runKeys.run(id),
    queryFn: async () => {
      const prev = qc.getQueryData<RunDetail>(runKeys.run(id))?.status;
      const run = await api.get<RunDetail>(`${BASE}/runs/${id}`, docParams(documentId));
      // 상태가 바뀌면(실행 중 → 확인 대기, 확인 대기 → 완료 등) 확인 대기 숫자를 바로 다시 센다(QA AUTO-B5)
      if (prev && prev !== run.status) qc.invalidateQueries({ queryKey: runKeys.pending });
      return run;
    },
    enabled,
    placeholderData: undefined,
    refetchInterval: (q) => (q.state.data ? pollMs(q.state.data.status, boost) : false),
    refetchIntervalInBackground: false,
  });
}

/** steps는 실행과 같은 주기로 함께 부른다(요구사항 3.7). 실행 상태를 받아 주기를 맞춘다 */
export function useRunSteps(id: number, status: RunStatus | undefined, boost = false, enabled = true, documentId?: number) {
  return useQuery({
    queryKey: runKeys.steps(id),
    queryFn: () => api.get<RunStep[]>(`${BASE}/runs/${id}/steps`, docParams(documentId)),
    enabled,
    placeholderData: undefined,
    refetchInterval: () => pollMs(status, boost),
    refetchIntervalInBackground: false,
  });
}

function useInvalidateRun(id: number) {
  const qc = useQueryClient();
  return () => {
    qc.invalidateQueries({ queryKey: runKeys.run(id) });
    qc.invalidateQueries({ queryKey: runKeys.steps(id) });
    qc.invalidateQueries({ queryKey: runKeys.pending });
  };
}

/** 사람 확인 재개(요청자만). data에는 고친 필드만 담는다. 202 응답 상태는 대개 WAITING이라 계속 폴링한다 */
export function useResumeRun(id: number) {
  const refresh = useInvalidateRun(id);
  return useMutation({
    mutationFn: (body: { approved: boolean; comment?: string; data?: Record<string, unknown>; receipts?: HitlReceiptAnswer[] }) =>
      api.post<{ runId: number; status: RunStatus }>(`${BASE}/runs/${id}/resume`, body),
    onSettled: refresh,
  });
}

/** 사람 확인 화면의 [다시 검토]: 고친 값·사실로 규정 검토만 미리 돌려 본다(실행은 계속 대기) */
export function usePolicyPreview(id: number) {
  return useMutation({
    mutationFn: (receipts: HitlReceiptAnswer[]) =>
      api.post<PolicyPreview[]>(`${BASE}/runs/${id}/policy-preview`, { receipts }, { timeout: 120_000 }),
  });
}

export function useCancelRun(id: number) {
  const refresh = useInvalidateRun(id);
  return useMutation({
    mutationFn: () => api.post<RunDetail>(`${BASE}/runs/${id}/cancel`),
    onSettled: refresh,
  });
}
