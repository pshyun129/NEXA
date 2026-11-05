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
export type MigrationEntity = 'HR_ORG' | 'HR_EMPLOYEE';
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
