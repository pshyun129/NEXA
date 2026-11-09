import type { AnomalyType, WorkTime } from '@/api/attendance';
import type { AttendanceRequestType } from '@/api/attendanceRequests';
import { kst } from '@/format';

export const REQUESTS_PATH = '/attendance/requests';
export const requestHref = (id: number) => `${REQUESTS_PATH}/${id}`;

export interface NewRequestPrefill {
  type: AttendanceRequestType;
  date?: string;
  /** HOLIDAY_SUBST 대체일 */
  subst?: string;
  /** TIME_FIX: 없는 출근·퇴근 기록을 빈 추가 항목으로 넣어 둔다(MISSING_CLOCK) */
  fillClock?: boolean;
  /** OVERTIME 구간(오프셋 포함 ISO) */
  start?: string;
  end?: string;
}

export function newRequestHref(p: NewRequestPrefill) {
  const q = new URLSearchParams({ type: p.type });
  if (p.date) q.set('date', p.date);
  if (p.subst) q.set('subst', p.subst);
  if (p.fillClock) q.set('fill', 'clock');
  if (p.start) q.set('start', p.start);
  if (p.end) q.set('end', p.end);
  return `${REQUESTS_PATH}/new?${q.toString()}`;
}

/** 서버로 보낼 시각: 서울 오프셋을 붙인 ISO(요구사항 3.3) */
export const toOffsetIso = (d: ReturnType<typeof kst>) => d.format('YYYY-MM-DDTHH:mm:ssZ');

interface DayLike { workDate: string; clockOut: string | null; workTime: WorkTime }

/**
 * 근태이상 칩 → 신청 딥링크(설계 6.4).
 * LATE·EARLY_LEAVE·MISSING_CLOCK → 근무시간 수정, UNAPPROVED_OT → 초과근무(퇴근 − 미승인분 ~ 퇴근).
 * 그 밖(UNDER_STD_HOURS 등)은 null: 일간 상세만 연다.
 */
export function anomalyHref(a: AnomalyType, day: DayLike): string | null {
  switch (a) {
    case 'LATE':
    case 'EARLY_LEAVE':
      return newRequestHref({ type: 'TIME_FIX', date: day.workDate });
    case 'MISSING_CLOCK':
      return newRequestHref({ type: 'TIME_FIX', date: day.workDate, fillClock: true });
    case 'UNAPPROVED_OT':
      return overtimeHref(day);
    default:
      return null;
  }
}

/** 미승인 초과근로를 초과근무 신청으로. 퇴근 기록이 있으면 구간을 채운다 */
export function overtimeHref(day: DayLike) {
  const min = day.workTime.unapprovedOtMin;
  if (!day.clockOut || !min) return newRequestHref({ type: 'OVERTIME', date: day.workDate });
  const end = kst(day.clockOut);
  return newRequestHref({
    type: 'OVERTIME', date: day.workDate,
    start: toOffsetIso(end.subtract(min, 'minute')), end: toOffsetIso(end),
  });
}
