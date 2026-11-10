import { useEffect, useRef } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import { attnKeys, type WorkTime } from './attendance';
import { usePollWindow } from '@/common/usePollWindow';

// attendance-service 근태 신청 DTO (AttendanceRequestController, 2026-11-05 develop 기준)

export type AttendanceRequestType = 'TIME_FIX' | 'OVERTIME' | 'HOLIDAY_SUBST';
export type AttendanceRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELED';
export type RequestItemAction = 'ADD' | 'MODIFY' | 'DELETE';

/** 신청 항목(preview·상신 공용). OVERTIME은 action 없이 statusCode·startAt·endAt */
export interface RequestItemInput {
  action?: RequestItemAction;
  targetLogId?: number;
  statusCode?: string;
  /** 오프셋 포함 ISO(2026-11-04T09:00:00+09:00). 근무일 당일 또는 다음 날만 */
  startAt?: string;
  endAt?: string;
}

export interface RequestInput {
  reqType: AttendanceRequestType;
  targetDate: string;
  substDate?: string;
  items?: RequestItemInput[];
}

/** AttendanceRequestPreviewResponse: 유형에 해당하는 칸만 온다 */
export interface RequestPreview {
  reqType: AttendanceRequestType;
  targetDate: string;
  timeFix?: { before: WorkTime; after: WorkTime; weeklyTotalMin: number; weeklyMaxMin: number };
  overtime?: { requestedMin: number; weeklyTotalMin: number; weeklyMaxMin: number; remainMin: number };
  holidaySubst?: { targetDate: string; substDate: string };
}

/** AttendanceRequestSummaryResponse(목록). approvalDocId는 없어서 상세에서 얻는다 */
export interface RequestSummary {
  attReqId: number;
  reqType: AttendanceRequestType;
  targetDate: string;
  substDate: string | null;
  reason: string;
  status: AttendanceRequestStatus;
  createdAt: string;
  completedAt: string | null;
}

export interface RequestDetailItem {
  itemId: number;
  action: RequestItemAction | null;
  targetLogId: number | null;
  statusCode: string;
  statusName: string;
  startAt: string | null;
  endAt: string | null;
}

export interface RequestDetail extends RequestSummary {
  approvalDocId: number | null;
  docNo: string | null;
  items: RequestDetailItem[];
}

/** WorkStatusHistoryResponse: 근무시간 수정의 targetLogId를 고르는 데 쓴다(일간 응답에는 logId가 없다) */
export interface WorkStatusLog {
  logId: number;
  workDate: string;
  statusCode: string;
  statusName: string;
  startAt: string;
  source: 'SELF' | 'ADMIN' | 'APPROVAL';
  createdBy: number | null;
  reason: string | null;
  isDeleted: boolean;
  createdAt: string;
}

const BASE = '/api/v1/attn/me/requests';

export const reqKeys = {
  all: ['attn', 'requests'] as const,
  list: (year: number, type?: AttendanceRequestType) => ['attn', 'requests', 'list', year, type ?? null] as const,
  detail: (id: number) => ['attn', 'requests', 'detail', id] as const,
  logs: (date: string) => ['attn', 'history', date] as const,
};

/** 결재 대기 행이 보이는 동안 5초 + 포커스로 다시 불러오고, 2분이 지나면 멈춘다(설계 7.3 진행 중 신청 목록) */
const POLL_MS = 5_000;

export function useMyRequests(year: number, type?: AttendanceRequestType) {
  const qc = useQueryClient();
  const key = reqKeys.list(year, type);
  // 폴링 여부는 직전 응답(캐시)으로 정한다. 응답이 바뀌면 useQuery 구독으로 다시 렌더된다
  const poll = usePollWindow(!!qc.getQueryData<RequestSummary[]>(key)?.some((r) => r.status === 'PENDING'));
  const q = useQuery({
    queryKey: key,
    queryFn: () => api.get<RequestSummary[]>(BASE, { params: { year, requestType: type } }),
    placeholderData: keepPreviousData,
    refetchInterval: poll.active ? () => (document.hidden ? false : POLL_MS) : false,
    refetchIntervalInBackground: false,
  });
  // 결재 결과가 반영된 신청이 늘면 근태(일간·주간·오늘)도 바뀌었으므로 다시 불러온다
  const done = q.data?.filter((r) => r.completedAt).map((r) => r.attReqId).join(',');
  const prevDone = useRef(done);
  useEffect(() => {
    if (prevDone.current !== undefined && done !== undefined && done !== prevDone.current) {
      qc.invalidateQueries({ queryKey: attnKeys.all, predicate: (x) => x.queryKey[1] !== 'requests' });
      // 결과가 새로 반영된 신청의 상세도 다시 읽는다(열린 Drawer가 "결재 대기"로 남지 않게, QA AR-139)
      const before = new Set((prevDone.current ?? '').split(',').filter(Boolean));
      for (const id of done.split(',').filter((x) => x && !before.has(x))) {
        qc.invalidateQueries({ queryKey: reqKeys.detail(Number(id)) });
      }
    }
    prevDone.current = done;
  }, [done, qc]);
  return { ...q, poll };
}

/** 상세. 결재 결과 반영을 기다리는 폴링은 AsyncSync가 refetch로 한다 */
export function useRequest(id: number | null) {
  return useQuery({
    queryKey: reqKeys.detail(id ?? 0),
    queryFn: () => api.get<RequestDetail>(`${BASE}/${id}`),
    enabled: id != null,
    placeholderData: undefined,
  });
}

/** 그날 상태 기록(삭제 제외). 근무시간 수정에서 수정·삭제 대상을 고른다 */
export function useDayLogs(date: string | null) {
  return useQuery({
    queryKey: reqKeys.logs(date ?? ''),
    queryFn: () => api.get<WorkStatusLog[]>('/api/v1/attn/me/history', { params: { from: date, to: date } }),
    enabled: !!date,
    select: (list) => list.filter((l) => !l.isDeleted && l.workDate === date),
    placeholderData: undefined,
  });
}

export function usePreviewRequest() {
  return useMutation({
    mutationFn: (input: RequestInput) => api.post<RequestPreview>(`${BASE}/preview`, input),
  });
}

export function useSubmitRequest() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: RequestInput & { reason: string }) => api.post<RequestDetail>(BASE, input),
    onSuccess: (res) => {
      qc.setQueryData(reqKeys.detail(res.attReqId), res);
      qc.invalidateQueries({ queryKey: reqKeys.all });
      qc.invalidateQueries({ queryKey: ['documents'] });
    },
  });
}

/** 회수는 동기 처리다. 응답으로 바로 갱신하고 비동기 대기 표시는 하지 않는다(요구사항 3.3) */
export function useCancelRequest() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api.post<RequestDetail>(`${BASE}/${id}/cancel`),
    onSuccess: (res) => {
      qc.setQueryData(reqKeys.detail(res.attReqId), res);
      qc.invalidateQueries({ queryKey: reqKeys.all });
      qc.invalidateQueries({ queryKey: ['documents'] });
      if (res.approvalDocId != null) qc.invalidateQueries({ queryKey: ['document', res.approvalDocId] });
    },
  });
}
