import { useMutation, useQuery, useQueryClient, keepPreviousData } from '@tanstack/react-query';
import { api } from './client';

// migration-service MigrationAdminController (/api/v1/admin/migrations, ADMIN, origin/develop 6e630e3 기준).
// 모두 동기 처리다. 업로드는 읽기·매핑 추천(LLM)·검증까지 한 번에 하므로 오래 걸릴 수 있다.
// LocalDateTime은 오프셋 없는 UTC(serverUtc로 해석, gaps B13).

const BASE = '/api/v1/admin/migrations';
const LONG = { timeout: 300_000 };

/** 단일 회사 ERP라 회사 ID를 상수로 보낸다. 서버가 토큰에서 정하게 되면 지운다(gaps B9) */
export const SINGLE_COMPANY_ID = 1;

export type MigrationStatus = 'DRAFT' | 'LOADED' | 'COMPLETED';
/** 화면에서 파일을 올리는 데이터 종류 */
export type MigrationEntity = 'HR_ORG' | 'HR_EMPLOYEE';
/**
 * 적재 묶음(batch)의 데이터 종류. 사원 파일을 적재하면 서버가 그 파일에서 뽑은 기준정보(HR_CODE) 묶음을
 * 같은 fileId로 사원 묶음 앞에 넣는다(origin/develop 2767c92 LoadService.units).
 */
export type BatchEntity = MigrationEntity | 'HR_CODE';
export type Severity = 'BLOCK' | 'WARN' | 'FIX';
export type IssueTotals = Record<Severity, number>;

export interface MigrationFileSummary { fileId: number; fileName: string; entity: string; rows: number; uploadedAt: string }
export interface BatchView {
  batchId: string; fileId: number; entity: string; status: 'LOADING' | 'LOADED' | 'FAILED' | 'ROLLED_BACK';
  sourceRows: number; created: number; updated: number; error: string | null; startedAt: string; finishedAt: string | null;
}
export interface MigrationDetail {
  migrationId: number; companyId: number; name: string; cutoverDate: string | null; status: MigrationStatus;
  createdAt: string; files: MigrationFileSummary[]; batches: BatchView[];
}

export interface SheetInfo { name: string; rows: number; headerRow: number; formulaCells: number }
export interface FileUploadResponse {
  fileId: number; entity: string; rows: number; headers: string[];
  /** 누락된 필수 필드의 라벨(필드명 아님) */
  missingRequired: string[];
  issueTotals: IssueTotals; sheetName: string | null; headerRow: number; totalRows: number[]; sheets: SheetInfo[];
}

export interface ColumnProfile { sourceColumn: string; detectedType: 'DATE' | 'NUMBER' | 'STRING' | 'EMPTY'; nullRate: number; distinctCount: number; dateFormats: string[] }
export interface ColumnMapping {
  sourceColumn: string; targetField: string | null; targetLabel: string | null; confirmed: boolean;
  suggestedBy: 'SYNONYM' | 'LLM' | 'USER' | null; reason: string | null;
}
export interface FileDetail {
  fileId: number; fileName: string; sheetName: string | null; headerRow: number | null; entity: string; rows: number; uploadedAt: string;
  columns: ColumnProfile[]; columnMappings: ColumnMapping[]; missingRequired: string[]; issueTotals: IssueTotals;
}

export interface IssueGroup {
  fileId: number; code: string; field: string | null; sourceColumn: string | null; severity: Severity; count: number;
  message: string; sampleRows: number[]; sampleValues: string[]; sampleSuggestion: string | null;
}
export interface ValidationIssue {
  fileId: number; rowNo: number | null; field: string | null; sourceColumn: string | null; severity: Severity;
  code: string; message: string; value: string | null; suggestion: string | null;
}
export interface IssueList {
  /** fileId로만 거른 합계(severity 필터 무시) */
  totals: IssueTotals;
  groups: IssueGroup[];
  content: ValidationIssue[];
  page: number; size: number; totalElements: number;
}
export interface IssueQuery { fileId?: number; severity?: Severity; page: number; size: number }

export const migKeys = {
  all: (id: number) => ['migrations', id] as const,
  detail: (id: number) => ['migrations', id, 'detail'] as const,
  file: (id: number, fileId: number) => ['migrations', id, 'file', fileId] as const,
  issues: (id: number, q: IssueQuery) => ['migrations', id, 'issues', q] as const,
};

export function useCreateMigration() {
  return useMutation({
    mutationFn: (body: { name: string; cutoverDate?: string }) =>
      api.post<{ migrationId: number; status: MigrationStatus }>(BASE, { companyId: SINGLE_COMPANY_ID, ...body }),
  });
}

export function useMigration(id: number) {
  return useQuery({ queryKey: migKeys.detail(id), queryFn: () => api.get<MigrationDetail>(`${BASE}/${id}`), placeholderData: undefined });
}

export function useMigrationFile(id: number, fileId: number | null) {
  return useQuery({
    queryKey: migKeys.file(id, fileId ?? 0),
    queryFn: () => api.get<FileDetail>(`${BASE}/${id}/files/${fileId}`),
    enabled: fileId != null,
    placeholderData: undefined,
  });
}

export function useIssues(id: number, q: IssueQuery, enabled = true) {
  return useQuery({
    queryKey: migKeys.issues(id, q),
    queryFn: () => api.get<IssueList>(`${BASE}/${id}/issues`, { params: q }),
    enabled,
    placeholderData: keepPreviousData,
  });
}

/** 한 작업의 모든 조회를 다시 부른다(업로드·매핑·자동 정리는 다른 파일의 검증 결과도 바꾼다) */
function useRefresh(id: number) {
  const qc = useQueryClient();
  return () => qc.invalidateQueries({ queryKey: migKeys.all(id) });
}

export function useUploadMigrationFile(id: number) {
  const refresh = useRefresh(id);
  return useMutation({
    mutationFn: ({ file, entity, sheet }: { file: File; entity: MigrationEntity; sheet?: string }) => {
      const fd = new FormData();
      fd.append('file', file);
      fd.append('entity', entity);
      if (sheet) fd.append('sheet', sheet);
      return api.post<FileUploadResponse>(`${BASE}/${id}/files`, fd, LONG);
    },
    onSettled: refresh,
  });
}

export function useSaveMapping(id: number, fileId: number) {
  const refresh = useRefresh(id);
  return useMutation({
    mutationFn: (columnMappings: { sourceColumn: string; targetField: string | null }[]) =>
      api.put<{ missingRequired: string[]; issueTotals: IssueTotals }>(`${BASE}/${id}/files/${fileId}/mappings`, { columnMappings }, LONG),
    onSettled: refresh,
  });
}

/** 자동 정리 적용. 빈 본문이면 작업 전체, fileId·code·field로 범위를 좁힌다. applied는 바뀐 셀 수 */
export function useApplyFixes(id: number) {
  const refresh = useRefresh(id);
  return useMutation({
    mutationFn: (scope: { fileId?: number; code?: string; field?: string | null }) =>
      api.post<{ applied: number; issueTotals: IssueTotals }>(`${BASE}/${id}/fixes`, scope, LONG),
    onSettled: refresh,
  });
}

/** 행 수정 결과. rowIssues: 수정 후 그 행에 남은 이슈(비어 있으면 해결) */
export interface RowUpdateResult { rowNo: number; rowIssues: ValidationIssue[]; issueTotals: IssueTotals }

/** 행 하나 직접 수정(P1, 설계 6.7 ✎). values는 원본 컬럼명 → 새 값, 빈 값이면 칸을 비운다. 서버가 파일 전체를 다시 검증한다.
 * 적재된 파일은 되돌린 뒤에만 고칠 수 있다(409 FILE_ALREADY_LOADED) */
export function useUpdateRow(id: number, fileId: number) {
  const refresh = useRefresh(id);
  return useMutation({
    mutationFn: ({ rowNo, values }: { rowNo: number; values: Record<string, string> }) =>
      api.patch<RowUpdateResult>(`${BASE}/${id}/files/${fileId}/rows/${rowNo}`, { values }, LONG),
    onSettled: refresh,
  });
}

/* ---------- ④ 미리보기 · ⑤ 적재 · ⑥ 대사 ---------- */

/** 미리보기(dry run). loadable = 적재 불가(BLOCK) 0건 + 자동 정리(FIX) 0건. sample은 정리된 값 예시 몇 행(필드명 키) */
export interface LoadPreview {
  loadable: boolean; blockIssues: number; fixIssues: number;
  files: { fileId: number; entity: string; rows: number; sample: Record<string, unknown>[] }[];
}
/** 적재 결과. 대상 서비스가 실패해도 HTTP 200 + batches[].status=FAILED로 온다(gaps B9, 계약) */
export interface LoadResult { status: MigrationStatus; batches: BatchView[] }
/** 대사: 묶음별 원본 행 수(source) vs 대상 서비스에 들어간 수(loaded) */
export interface ReconciliationItem { batchId: string; entity: string; metric: string; source: number; loaded: number; match: boolean }

/** fileIds를 비우면 작업 전체가 대상이다. 화면은 entity 탭마다 그 파일 하나만 보낸다(조직 → 사원 순서를 사람이 지킨다) */
export function usePreviewLoad(id: number) {
  return useMutation({
    mutationFn: (fileIds: number[]) => api.post<LoadPreview>(`${BASE}/${id}/loads`, { fileIds }, { ...LONG, params: { dryRun: true } }),
  });
}

export function useLoad(id: number) {
  const refresh = useRefresh(id);
  return useMutation({
    mutationFn: (fileIds: number[]) => api.post<LoadResult>(`${BASE}/${id}/loads`, { fileIds }, { ...LONG, params: { dryRun: false } }),
    onSettled: refresh,
  });
}

/**
 * 적재 묶음 되돌리기(P1). 적재됨(LOADED) 묶음만 되고(409 LOAD_005), 나중 순서 묶음이 적재돼 있으면 409 LOAD_006.
 * 적재된 묶음이 하나도 안 남으면 작업 상태가 DRAFT로 돌아간다. 응답은 적재와 같은 모양(작업 전체 묶음)
 */
export function useRollbackBatch(id: number) {
  const refresh = useRefresh(id);
  return useMutation({
    mutationFn: (batchId: string) => api.delete<LoadResult>(`${BASE}/${id}/batches/${encodeURIComponent(batchId)}`, LONG),
    onSettled: refresh,
  });
}

/**
 * 기준정보 계획: 사원 파일에서 뽑은 직위·직급·직책·직무를 대상과 비교한다. 기준정보가 아직 적재되지 않은 사원 파일만 나온다.
 * AMBIGUOUS가 하나라도 있으면 적재가 409 LOAD_009로 막힌다. GRADE의 value는 'G3 2호봉'처럼 호봉까지 붙는다.
 */
export type ReferenceType = 'POSITION' | 'GRADE' | 'DUTY' | 'JOB_CODE';
export interface ReferencePlanItem {
  fileId: number; type: ReferenceType; value: string; rankOrder: number | null;
  status: 'NEW' | 'EXISTS' | 'AMBIGUOUS'; message: string | null;
}

export function useReferencePlan(id: number, enabled: boolean) {
  return useQuery({
    queryKey: [...migKeys.all(id), 'references'] as const,
    queryFn: () => api.get<{ items: ReferencePlanItem[] }>(`${BASE}/${id}/references`).then((r) => r.items),
    enabled,
  });
}

export function useReconciliation(id: number, enabled: boolean) {
  return useQuery({
    queryKey: [...migKeys.all(id), 'reconciliation'] as const,
    queryFn: () => api.get<ReconciliationItem[]>(`${BASE}/${id}/reconciliation`),
    enabled,
  });
}
