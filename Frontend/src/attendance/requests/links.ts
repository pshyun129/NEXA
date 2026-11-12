import type { AnomalyType, DayWorkTime } from '@/api/attendance';
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
  /** OVERTIME 구간(오프셋 포함 ISO). 여러 구간이면 ranges */
  start?: string;
  end?: string;
  ranges?: { start: string; end: string }[];
}

export function newRequestHref(p: NewRequestPrefill) {
  const q = new URLSearchParams({ type: p.type });
  if (p.date) q.set('date', p.date);
  if (p.subst) q.set('subst', p.subst);
  if (p.fillClock) q.set('fill', 'clock');
  const ranges = p.ranges ?? (p.start || p.end ? [{ start: p.start ?? '', end: p.end ?? '' }] : []);
  // 구간마다 start·end를 같은 순서로 반복해서 넣는다(신청 화면이 getAll로 짝지어 읽음)
  for (const r of ranges) {
    q.append('start', r.start);
    q.append('end', r.end);
  }
  return `${REQUESTS_PATH}/new?${q.toString()}`;
}

/** 서버로 보낼 시각: 서울 오프셋을 붙인 ISO(요구사항 3.3) */
export const toOffsetIso = (d: ReturnType<typeof kst>) => d.format('YYYY-MM-DDTHH:mm:ssZ');

interface DayLike { workDate: string; clockOut: string | null; workTime: DayWorkTime }

/**
 * 근태이상 칩 → 신청 딥링크(설계 6.4).
 * LATE·EARLY_LEAVE·MISSING_CLOCK → 근무시간 수정, UNAPPROVED_OT → 초과근무(서버가 준 미승인 구간).
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

/**
 * 미승인 초과근로를 초과근무 신청으로. 서버가 준 미승인 구간으로 채운다(이미 승인된 구간과 겹치지 않게).
 * 구간 정보가 없는 응답이면 예전처럼 퇴근 − 미승인분 ~ 퇴근으로 채운다.
 */
export function overtimeHref(day: DayLike) {
  const min = day.workTime.unapprovedOtMin;
  if (!day.clockOut || !min) return newRequestHref({ type: 'OVERTIME', date: day.workDate });
  const ranges = day.workTime.unapprovedOtRanges;
  if (ranges && ranges.length > 0) {
    return newRequestHref({
      type: 'OVERTIME', date: day.workDate,
      ranges: ranges.map((r) => ({ start: toOffsetIso(kst(r.startAt)), end: toOffsetIso(kst(r.endAt)) })),
    });
  }
  const end = kst(day.clockOut);
  return newRequestHref({
    type: 'OVERTIME', date: day.workDate,
    start: toOffsetIso(end.subtract(min, 'minute')), end: toOffsetIso(end),
  });
}
