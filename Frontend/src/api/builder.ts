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
  return useMutation({
    mutationFn: (body: { inputs: Record<string, unknown>; fileIds: number[] }) =>
      api.post<{ runId: number; status: string }>(`${BASE}/workflows/${id}/test-runs`, body),
  });
}
