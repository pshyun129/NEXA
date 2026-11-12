import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import type { FormField, NodeSummary, WorkflowDetail, WorkflowSummary } from './ai';

// 자동화 빌더(요구사항 4.6, 설계 6.6). FastAPI workflows/·nodes/ 라우터(develop b772e32) 기준.
// 그래프·설정 값(config)은 서버가 그대로 저장하므로 키는 snake_case 그대로 둔다.

const BASE = '/api/v1/ai';

export interface GraphNode {
  id: string;
  type: string;
  position: { x: number; y: number };
  config: Record<string, unknown>;
}
export interface GraphEdge {
  id: string;
  source: string;
  target: string;
  /** 분기 노드에서 나가는 선이면 카탈로그 branches 문자열 그대로 */
  branch: string | null;
}
export interface Graph { nodes: GraphNode[]; edges: GraphEdge[] }

export interface BuilderWorkflow extends WorkflowDetail {
  draftGraph: Graph;
  inputForm: FormField[];
}

export interface NodeCategory { code: string; name: string }

export interface ConfigField {
  key: string;
  label: string;
  /** text·number·select·ref(앞 노드 값 참조, 예: {{n3.fields}}) */
  type: 'text' | 'number' | 'select' | 'ref' | string;
  required: boolean;
  options: string[];
}
export interface OutputVar { key: string; label: string; type: string }
export interface NodeDetail extends NodeSummary {
  configFields: ConfigField[];
  branches: string[];
  outputs: OutputVar[];
}

export interface ValidationIssue { nodeId: string | null; edgeId: string | null; message: string }
export interface ValidationResult { valid: boolean; issues: ValidationIssue[] }

export const builderKeys = {
  list: ['workflows', 'all'] as const,
  detail: (id: number) => ['workflows', id] as const,
};

export function useWorkflowList() {
  return useQuery({
    queryKey: builderKeys.list,
    queryFn: () => api.get<WorkflowSummary[]>(`${BASE}/workflows`),
  });
}

/** 편집기는 서버 초안을 기준으로 하므로 캐시를 오래 두지 않는다(실행 화면의 useWorkflow와 키는 같다) */
/** 캐시를 거치지 않고 서버의 지금 초안을 읽는다(저장 전에 다른 창의 변경을 확인할 때) */
export const fetchBuilderWorkflow = (id: number) => api.get<BuilderWorkflow>(`${BASE}/workflows/${id}`);

export function useBuilderWorkflow(id: number) {
  return useQuery({
    queryKey: builderKeys.detail(id),
    queryFn: () => api.get<BuilderWorkflow>(`${BASE}/workflows/${id}`),
    staleTime: 0,
  });
}

export function useNodeCategories() {
  return useQuery({
    queryKey: ['nodes', 'categories'],
    queryFn: () => api.get<NodeCategory[]>(`${BASE}/nodes/categories`),
    staleTime: 60 * 60_000,
  });
}

/**
 * 노드 전체의 상세(설정 칸·분기·출력). 목록 API에는 branches·outputs가 없어 종류마다 한 번씩 부른다(10여 종, 1시간 캐시).
 * 캔버스의 분기 출구와 순서 경고가 모든 노드의 상세를 필요로 해서 화면을 열 때 한꺼번에 받는다.
 */
export function useNodeDetails() {
  const list = useQuery({
    queryKey: ['nodes'],
    queryFn: () => api.get<NodeSummary[]>(`${BASE}/nodes`),
    staleTime: 60 * 60_000,
  });
  const details = useQueries({
    queries: (list.data ?? []).map((n) => ({
      queryKey: ['nodes', 'detail', n.nodeType],
      queryFn: () => api.get<NodeDetail>(`${BASE}/nodes/${n.nodeType}`),
      staleTime: 60 * 60_000,
    })),
  });
  const ready = list.isSuccess && details.every((d) => d.isSuccess);
  const error = list.error ?? details.find((d) => d.error)?.error ?? null;
  return {
    /** 카탈로그 순서 그대로 */
    nodes: ready ? details.map((d) => d.data as NodeDetail) : undefined,
    byType: ready ? new Map(details.map((d) => [d.data!.nodeType, d.data!])) : undefined,
    error,
    refetch: () => { list.refetch(); details.forEach((d) => d.refetch()); },
  };
}

/** 초안 저장. 서버는 저장할 때 검사하지 않는다 */
export function useSaveGraph(id: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (graph: Graph) => api.put<BuilderWorkflow>(`${BASE}/workflows/${id}/graph`, graph),
    onSuccess: (wf) => {
      qc.setQueryData(builderKeys.detail(id), wf);
      qc.invalidateQueries({ queryKey: builderKeys.list });
    },
  });
}

/** 서버 검사는 저장된 초안을 본다. 저장 안 한 변경이 있으면 먼저 저장해야 한다 */
export function useValidateGraph(id: number) {
  return useMutation({
    mutationFn: () => api.post<ValidationResult>(`${BASE}/workflows/${id}/validate`),
  });
}

/** 초안 그래프로 테스트 실행(배포 버전이 아님) */
export function useStartTestRun(id: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { inputs: Record<string, unknown>; fileIds: number[] }) =>
      api.post<{ runId: number; status: string }>(`${BASE}/workflows/${id}/test-runs`, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['runs', 'me'] });
    },
  });
}

/* ---------- 배포·버전 조회(P1) ---------- */

/** 버전 목록(GET /workflows/{id}/versions). active = 지금 사원 실행에 쓰이는 버전. created_at은 오프셋 없는 UTC */
export interface WorkflowVersion { version: number; createdAt: string; active: boolean }

export function useWorkflowVersions(id: number) {
  return useQuery({
    queryKey: [...builderKeys.detail(id), 'versions'] as const,
    queryFn: () => api.get<WorkflowVersion[]>(`${BASE}/workflows/${id}/versions`),
  });
}

/**
 * 배포(POST /workflows/{id}/deploy): 저장된 초안을 새 버전으로 복사하고 활성 버전으로 바꾼다.
 * 서버도 검사해서 이슈가 있으면 WF_INVALID_GRAPH(첫 이슈 메시지). 이미 시작된 실행은 자기 버전 그래프로 계속 간다
 */
export function useDeployWorkflow(id: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<{ version: number }>(`${BASE}/workflows/${id}/deploy`),
    onSettled: () => Promise.all([
      qc.invalidateQueries({ queryKey: builderKeys.detail(id) }),
      qc.invalidateQueries({ queryKey: builderKeys.list }),
      qc.invalidateQueries({ queryKey: ['workflows', 'available'] }),
    ]),
  });
}

/* ---------- 노출 화면(placements) ---------- */

/**
 * 노출 화면 저장(PUT /workflows/{id}/placements). 사원 화면 목록(GET /workflows/available?screen=)은
 * 배포된 버전이 있고 placements에 그 화면 코드가 있는 워크플로만 준다. 배포만으로는 보이지 않는다.
 */
export function useSavePlacements(id: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (screens: string[]) => api.put<BuilderWorkflow>(`${BASE}/workflows/${id}/placements`, { screens }),
    onSuccess: (wf) => qc.setQueryData(builderKeys.detail(id), wf),
    onSettled: () => Promise.all([
      qc.invalidateQueries({ queryKey: builderKeys.list }),
      qc.invalidateQueries({ queryKey: ['workflows', 'available'] }),
    ]),
  });
}

/** 여러 워크플로 상세(노출 화면 확인용, 관리 홈). 키는 편집기와 같다 */
export function useBuilderWorkflows(ids: number[]) {
  return useQueries({
    queries: ids.map((id) => ({
      queryKey: builderKeys.detail(id),
      queryFn: () => api.get<BuilderWorkflow>(`${BASE}/workflows/${id}`),
      staleTime: 30_000,
    })),
  });
}

/** 사원 실행 입력 폼 저장(PUT /workflows/{id}/input-form). 전체 목록 덮어쓰기. 배포와 별개로 바로 반영된다 */
export function useSaveInputForm(id: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (fields: FormField[]) => api.put<BuilderWorkflow>(`${BASE}/workflows/${id}/input-form`, { fields }),
    onSuccess: (wf) => qc.setQueryData(builderKeys.detail(id), wf),
    onSettled: () => qc.invalidateQueries({ queryKey: ['workflows', id, 'input-form'] }),
  });
}

/* ---------- 워크플로 만들기·고치기·지우기·복제·되돌리기 (FastAPI workflows/routers.py 1·5·6·11-1·11-3) ---------- */

/** 새 워크플로(POST /workflows). 이름 1~100자. 빈 초안 그래프로 만들어진다 */
export function useCreateWorkflow() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { name: string; description: string | null }) => api.post<BuilderWorkflow>(`${BASE}/workflows`, body),
    onSuccess: (wf) => qc.setQueryData(builderKeys.detail(wf.id), wf),
    onSettled: () => qc.invalidateQueries({ queryKey: builderKeys.list }),
  });
}

/** 이름·설명 수정(PATCH /workflows/{id}). 보낸 칸만 바뀐다 */
export function useUpdateWorkflow(id: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { name?: string; description?: string | null }) => api.patch<BuilderWorkflow>(`${BASE}/workflows/${id}`, body),
    onSuccess: (wf) => qc.setQueryData(builderKeys.detail(id), wf),
    onSettled: () => Promise.all([
      qc.invalidateQueries({ queryKey: builderKeys.list }),
      qc.invalidateQueries({ queryKey: ['workflows', 'available'] }),
    ]),
  });
}

/** 삭제(DELETE /workflows/{id}). 실행 기록이 있으면 서버가 409 WF_005로 거절한다 */
export function useDeleteWorkflow() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api.delete<null>(`${BASE}/workflows/${id}`),
    onSuccess: (_, id) => qc.removeQueries({ queryKey: builderKeys.detail(id), exact: true }),
    onSettled: () => Promise.all([
      qc.invalidateQueries({ queryKey: builderKeys.list }),
      qc.invalidateQueries({ queryKey: ['workflows', 'available'] }),
    ]),
  });
}

/** 복제(POST /workflows/{id}/duplicate). 초안 그래프·입력 폼을 복사한 "(복사본)" 워크플로가 생긴다(배포 전) */
export function useDuplicateWorkflow() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api.post<BuilderWorkflow>(`${BASE}/workflows/${id}/duplicate`),
    onSuccess: (wf) => qc.setQueryData(builderKeys.detail(wf.id), wf),
    onSettled: () => qc.invalidateQueries({ queryKey: builderKeys.list }),
  });
}

/** 이전 버전으로 되돌리기(POST /workflows/{id}/versions/{v}/activate). 이후 시작하는 사원 실행부터 그 버전을 쓴다 */
export function useActivateVersion(id: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (version: number) => api.post<{ version: number }>(`${BASE}/workflows/${id}/versions/${version}/activate`),
    onSettled: () => Promise.all([
      qc.invalidateQueries({ queryKey: builderKeys.detail(id) }),
      qc.invalidateQueries({ queryKey: builderKeys.list }),
      qc.invalidateQueries({ queryKey: ['workflows', 'available'] }),
    ]),
  });
}
