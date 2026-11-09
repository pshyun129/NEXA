// 모든 표기 함수는 여기에만 둔다(style-guide 4.5). 화면에서 toLocaleString을 직접 부르지 않는다.
import dayjs, { type Dayjs } from 'dayjs';
import utc from 'dayjs/plugin/utc';
import timezone from 'dayjs/plugin/timezone';
import isoWeek from 'dayjs/plugin/isoWeek';
import 'dayjs/locale/ko';

dayjs.extend(utc);
dayjs.extend(timezone);
dayjs.extend(isoWeek);
dayjs.locale('ko');
export const TZ = 'Asia/Seoul';
dayjs.tz.setDefault(TZ);

const num = new Intl.NumberFormat('ko-KR');
const DASH = '—';

/** 서버 문자열/Date → 서울 시간 Dayjs. leave의 `...T15:00:00Z`도 서울 날짜로 바뀐다(요구사항 1.4). */
export const kst = (v: string | number | Date | Dayjs) => dayjs(v).tz(TZ);
export const nowKst = () => dayjs().tz(TZ);

/**
 * 오프셋 없는 서버 시각(FastAPI·migration의 naive TIMESTAMP/LocalDateTime)은 UTC로 해석한다.
 * 근거: 해당 컨테이너에 TZ 설정이 없어 UTC 기준으로 기록된다(S15P3log/frontend-backend-gaps.md B13).
 * 이미 오프셋(Z, +09:00)이 있으면 그대로 둔다.
 */
export function serverUtc<T extends string | null | undefined>(v: T): T {
  if (!v || /(Z|[+-]\d{2}:?\d{2})$/.test(v)) return v;
  return `${v}Z` as T;
}

/* ---------- 숫자·금액 ---------- */
const minus = (n: number, s: string) => (n < 0 ? `-${s}` : s); // ASCII '-'
export const formatNumber = (n: number | null | undefined) => (n == null ? DASH : minus(n, num.format(Math.abs(n))));
/** 문장·카드: 128,000원 */
export const formatMoney = (n: number | null | undefined) => (n == null ? DASH : `${formatNumber(n)}원`);
/** 건수·인원: 1,252행, 12건 */
export const formatCount = (n: number | null | undefined, unit: string) => (n == null ? DASH : `${formatNumber(n)}${unit}`);
/** 휴가 일수: 11.5일 (입력은 분, 하루=480분) */
export const formatLeaveDays = (minutes: number | null | undefined) =>
  minutes == null ? DASH : `${minus(minutes, (Math.abs(minutes) / 480).toFixed(1))}일`;

/* ---------- 근로시간(분) ---------- */
/** 문장: 8시간 10분 / 8시간 / 45분 */
export function formatDuration(min: number | null | undefined): string {
  if (min == null) return DASH;
  const a = Math.abs(Math.round(min));
  const h = Math.floor(a / 60);
  const m = a % 60;
  const s = h && m ? `${h}시간 ${m}분` : h ? `${h}시간` : `${m}분`;
  return minus(min, s);
}
/** 표·칩: 8h 10m / 8h / 45m */
export function formatDurationShort(min: number | null | undefined): string {
  if (min == null) return DASH;
  const a = Math.abs(Math.round(min));
  const h = Math.floor(a / 60);
  const m = a % 60;
  const s = h && m ? `${h}h ${m}m` : h ? `${h}h` : `${m}m`;
  return minus(min, s);
}
/** 실행 소요(ms): 2.1s / 1m 12s */
export function formatElapsed(ms: number): string {
  const sec = ms / 1000;
  if (sec < 60) return `${sec.toFixed(1)}s`;
  const m = Math.floor(sec / 60);
  return `${m}m ${Math.floor(sec % 60)}s`;
}

/* ---------- 날짜·시각 ---------- */
/** 2026.11.04(수) */
export const formatDate = (v: string | Date | Dayjs | null | undefined) => (v ? kst(v).format('YYYY.MM.DD(dd)') : DASH);
/** 표: 올해는 11.04(수), 다른 해는 2025.11.04 */
export function formatDateShort(v: string | Date | Dayjs | null | undefined) {
  if (!v) return DASH;
  const d = kst(v);
  return d.year() === nowKst().year() ? d.format('MM.DD(dd)') : d.format('YYYY.MM.DD');
}
/** 08:52 (실행 기록은 seconds=true) */
export const formatTime = (v: string | Date | Dayjs | null | undefined, seconds = false) =>
  v ? kst(v).format(seconds ? 'HH:mm:ss' : 'HH:mm') : DASH;
/** 2026.11.04 10:12 (표 올해는 11.04 10:12) */
export function formatDateTime(v: string | Date | Dayjs | null | undefined, short = false) {
  if (!v) return DASH;
  const d = kst(v);
  return short && d.year() === nowKst().year() ? d.format('MM.DD HH:mm') : d.format('YYYY.MM.DD HH:mm');
}
/** 범위: 11.10(월) ~ 11.12(수) */
export const formatRange = (a: string, b: string) => `${a} ~ ${b}`;
/** 방금 / n분 전 / 일시 */
export function formatRelative(v: string | Date | Dayjs) {
  const diff = nowKst().diff(kst(v), 'second');
  if (diff < 60) return '방금';
  if (diff < 3600) return `${Math.floor(diff / 60)}분 전`;
  return formatDateTime(v, true);
}
/** AsyncSync: 15초 전 확인 */
export const formatCheckedAgo = (sec: number) => `${Math.max(0, Math.floor(sec))}초 전 확인`;
