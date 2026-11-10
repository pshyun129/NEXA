import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import type { WorkStatusLog } from './attendanceRequests';

// attendance-service DTO (domain/attendance/dto, 2026-11-05 develop 기준)

export interface WorkStatusCode {
  statusCode: string;
  statusName: string;
  countAsWork: boolean;
}

/** AttendanceStatusResponse: today, clock-in, clock-out, status 공통 */
export interface AttendanceStatus {
  workDate: string;
  statusCode: string | null;
  statusName: string | null;
  isWorking: boolean;
  clockIn: string | null;
  clockOut: string | null;
  ableStatusList: WorkStatusCode[];
}

/** WorkTimeResponse (분) */
export interface WorkTime {
  totalMin: number;
  regularMin: number;
  nightMin: number;
  otExtendedMin: number;
  otNightMin: number;
  otHolidayMin: number;
  breakMin: number;
  excludedMin: number;
  approvedOtMin: number;
  unapprovedOtMin: number;
}

/** 일간·주간·월간 조회의 근로시간: 숫자 칸 + 미승인 초과근로가 있는 시간 구간(초과근무 신청을 미리 채울 때 쓴다) */
export interface DayWorkTime extends WorkTime {
  unapprovedOtRanges?: { startAt: string; endAt: string }[];
}

export type AnomalyType =
  | 'LATE' | 'EARLY_LEAVE' | 'MISSING_CLOCK' | 'UNDER_STD_HOURS' | 'UNAPPROVED_OT' | 'SHORT_BREAK' | 'WORK_ON_LEAVE';

export interface WorkStatusRecord {
  statusCode: string;
  statusName: string;
  countAsWork: boolean;
  startAt: string;
}

export interface DailyAttendance {
  workDate: string;
  workGroupName: string;
  isHoliday: boolean;
  clockIn: string | null;
  clockOut: string | null;
  workTime: DayWorkTime;
  records: WorkStatusRecord[];
  anomalies: AnomalyType[];
}

export interface DayLeave {
  leaveTypeName: string;
  portion: 'FULL' | 'AM_HALF' | 'PM_HALF';
  isPaid: boolean;
}

export interface AttendanceDaySummary {
  workDate: string;
  isHoliday: boolean;
  clockIn: string | null;
  clockOut: string | null;
  workTime: DayWorkTime;
  anomalies: AnomalyType[];
  leaves: DayLeave[];
}

export interface WeeklyAttendance {
  startDate: string;
  endDate: string;
  workGroupName: string;
  summary: {
    totalMin: number;
    approvedOtMin: number;
    unapprovedOtMin: number;
    stdMin: number;
    maxMin: number;
    remainStdMin: number;
    workDays: number;
    remainWorkDays: number;
  };
  days: AttendanceDaySummary[];
}

/** 월간(1일~말일). days는 주간과 같은 모양 */
export interface MonthlyAttendance {
  month: string;
  startDate: string;
  endDate: string;
  workGroupName: string;
  summary: {
    totalMin: number;
    approvedOtMin: number;
    unapprovedOtMin: number;
    stdMin: number;
    remainStdMin: number;
    workDays: number;
    remainWorkDays: number;
  };
  days: AttendanceDaySummary[];
}

/** 휴가를 쓸 수 없는 날(공휴일 + 내 대체휴일). type: LEGAL 법정, COMPANY 회사 지정, SUBSTITUTE 대체휴일 */
export interface Holiday { holidayDate: string; name: string; type: 'LEGAL' | 'COMPANY' | 'SUBSTITUTE' }

const BASE = '/api/v1/attn';

export const attnKeys = {
  all: ['attn'] as const,
  today: ['attn', 'today'] as const,
  daily: (date: string) => ['attn', 'daily', date] as const,
  weekly: (date: string) => ['attn', 'weekly', date] as const,
};

export function useToday() {
  return useQuery({
    queryKey: attnKeys.today,
    queryFn: () => api.get<AttendanceStatus>(`${BASE}/today`),
    // 탑바 칩: 60초 + 포커스, 숨겨진 탭은 중단(설계 7.3 전역 카운트와 같은 주기)
    refetchInterval: () => (document.hidden ? false : 60_000),
    refetchIntervalInBackground: false,
  });
}

export function useDaily(date: string) {
  return useQuery({
    queryKey: attnKeys.daily(date),
    queryFn: () => api.get<DailyAttendance>(`${BASE}/me/daily/${date}`),
  });
}

/** date가 속한 주(월~일). 키는 같은 주라도 date별로 갈리지 않게 호출 측에서 월요일을 넘긴다. */
export function useWeekly(date: string) {
  return useQuery({
    queryKey: attnKeys.weekly(date),
    queryFn: () => api.get<WeeklyAttendance>(`${BASE}/me/weekly`, { params: { date } }),
  });
}

/** 월간(P1, 요구사항 3.2). month = YYYY-MM */
export function useMonthly(month: string) {
  return useQuery({
    queryKey: [...attnKeys.all, 'monthly', month] as const,
    queryFn: () => api.get<MonthlyAttendance>(`${BASE}/me/monthly`, { params: { month } }),
  });
}

export function useHolidays(startDate: string, endDate: string) {
  return useQuery({
    queryKey: [...attnKeys.all, 'holidays', startDate, endDate] as const,
    queryFn: () => api.get<Holiday[]>(`${BASE}/holidays`, { params: { startDate, endDate } }),
    staleTime: 60 * 60_000,
  });
}

/** 상태 변경 이력(P1). 최대 31일, 삭제된 기록 포함(isDeleted) */
export function useStatusHistory(from: string, to: string) {
  return useQuery({
    queryKey: [...attnKeys.all, 'history', 'range', from, to] as const,
    queryFn: () => api.get<WorkStatusLog[]>(`${BASE}/me/history`, { params: { from, to } }),
  });
}

type ClockAction = { kind: 'in' } | { kind: 'out' } | { kind: 'status'; statusCode: string };

/** 출근·퇴근·상태 변경. 응답(AttendanceStatus)으로 today를 바로 갱신하고 일간·주간은 다시 불러온다. */
export function useClockAction() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (a: ClockAction) =>
      a.kind === 'in'
        ? api.post<AttendanceStatus>(`${BASE}/clock-in`)
        : a.kind === 'out'
          ? api.post<AttendanceStatus>(`${BASE}/clock-out`)
          : api.post<AttendanceStatus>(`${BASE}/status`, { statusCode: a.statusCode }),
    onSuccess: (res) => {
      qc.setQueryData(attnKeys.today, res);
      qc.invalidateQueries({ queryKey: ['attn', 'daily'] });
      qc.invalidateQueries({ queryKey: ['attn', 'weekly'] });
    },
  });
}
