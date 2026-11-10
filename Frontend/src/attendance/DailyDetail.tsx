import { Skeleton } from 'antd';
import { useDaily, type DailyAttendance, type WorkTime } from '@/api/attendance';
import { formatDuration, formatDurationShort, formatTime, kst, nowKst } from '@/format';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { EmptyState } from '@/ui/primitives';
import { AnomalyChips, UnapprovedOtCta } from './AnomalyChips';
import s from './attendance.module.css';

interface Span { start: number; end: number; code: string; name: string; work: boolean; open: boolean }

/** 상태 기록(시간순)을 구간으로 바꾼다. 분 단위, 근무일 00:00(서울) 기준. 자정 넘긴 퇴근은 1440 이상이 된다. */
function toSpans(d: DailyAttendance): Span[] {
  const base = kst(d.workDate).startOf('day');
  const min = (v: string) => kst(v).diff(base, 'minute');
  const recs = d.records.filter((r) => r.statusCode !== 'CLOCK_OUT');
  const endAll = d.clockOut ? min(d.clockOut) : null;
  const isToday = kst(d.workDate).isSame(nowKst(), 'day');
  return recs.map((r, i) => {
    const next = recs[i + 1];
    const start = min(r.startAt);
    const open = !next && endAll == null;
    const end = next ? min(next.startAt) : endAll ?? (isToday ? nowKst().diff(base, 'minute') : start);
    return { start, end: Math.max(start, end), code: r.statusCode, name: r.statusName, work: r.countAsWork, open };
  })
    // 퇴근취소는 취소된 퇴근 기록이라 구간으로 그리지 않는다(다음 상태까지는 빈칸, 서버도 근로에서 뺌)
    .filter((sp) => sp.code !== 'CLOCK_OUT_CANCEL');
}

function spanColor(sp: Span) {
  if (sp.code === 'LEAVE') return 'var(--nx-info-bd)';
  return sp.work ? 'var(--nx-brand-500)' : 'var(--nx-n-300)';
}

/** 일간 타임라인(일간 API의 records, 실제 구간). */
function Timeline({ d }: { d: DailyAttendance }) {
  const spans = toSpans(d);
  if (spans.length === 0) return null;
  const from = Math.min(6 * 60, Math.floor(spans[0].start / 60) * 60);
  const to = Math.max(22 * 60, Math.ceil(spans[spans.length - 1].end / 60) * 60);
  const pct = (m: number) => `${((m - from) / (to - from)) * 100}%`;
  const ticks: number[] = [];
  for (let h = from; h <= to; h += 120) ticks.push(h);
  return (
    <div>
      <div className={s.tl} role="img" aria-label="근무 상태 타임라인">
        {spans.map((sp, i) => (
          <div key={i} className={[s.tlSeg, sp.open ? s.tlOpen : ''].join(' ')}
               title={`${sp.name} ${formatTime(kst(d.workDate).startOf('day').add(sp.start, 'minute'))}~`}
               style={{ left: pct(sp.start), width: `calc(${pct(sp.end)} - ${pct(sp.start)})`, background: spanColor(sp) }} />
        ))}
      </div>
      <div className={s.tlAxis}>
        {ticks.map((m) => (
          <span key={m} style={{ left: pct(m) }} className="nx-num">{String(Math.floor(m / 60) % 24).padStart(2, '0')}</span>
        ))}
      </div>
      <ol className={s.recList}>
        {d.records.map((r, i) => (
          <li key={i}>
            <span className="nx-num">{formatTime(r.startAt)}</span>
            <span>{r.statusName}</span>
            {r.statusCode === 'CLOCK_OUT_CANCEL'
              ? <span className={s.muted}>취소된 퇴근</span>
              : !r.countAsWork && r.statusCode !== 'CLOCK_OUT' && <span className={s.muted}>근로 미포함</span>}
          </li>
        ))}
      </ol>
    </div>
  );
}

const WT_ROWS: [keyof WorkTime, string][] = [
  ['totalMin', '총 근로'], ['regularMin', '소정'], ['nightMin', '소정 중 야간'], ['otExtendedMin', '연장'],
  ['otNightMin', '초과 야간'], ['otHolidayMin', '휴일'], ['breakMin', '휴게'], ['excludedMin', '근로 미포함'],
  ['approvedOtMin', '승인 초과'], ['unapprovedOtMin', '미승인 초과'],
];

export function WorkTimeTable({ wt }: { wt: WorkTime }) {
  return (
    <dl className={s.wt}>
      {WT_ROWS.map(([k, label]) => (
        <div key={k} className={k === 'totalMin' ? s.wtStrong : undefined}>
          <dt>{label}</dt>
          <dd className={['nx-num', k === 'unapprovedOtMin' && wt[k] > 0 ? s.danger : ''].join(' ')}>
            {wt[k] ? formatDurationShort(wt[k]) : '—'}
          </dd>
        </div>
      ))}
    </dl>
  );
}

/** 하루 근태 상세: 출퇴근, 이상, 타임라인, 근로시간 분해. 오늘 탭과 주간 Drawer가 같이 쓴다. */
export function DailyDetail({ date }: { date: string }) {
  const q = useDaily(date);
  if (q.isPending) return <Skeleton active={false} paragraph={{ rows: 6 }} />;
  if (q.isError) return <ErrorPanel error={q.error} onRetry={() => q.refetch()} />;
  const d = q.data;
  const noRecord = !d.clockIn && d.records.length === 0;
  return (
    <div className={s.daily}>
      <div className={s.dailyHead}>
        <span>출근 <b className="nx-num">{formatTime(d.clockIn)}</b></span>
        <span>퇴근 <b className="nx-num">{formatTime(d.clockOut)}</b></span>
        <span>총 <b className="nx-num">{formatDuration(d.workTime.totalMin)}</b></span>
        {d.isHoliday && <span className={s.tag}>휴일</span>}
        <AnomalyChips day={d} />
        <span className={s.muted}>{d.workGroupName}</span>
      </div>
      {noRecord ? (
        <EmptyState title="근태 기록이 없습니다" description={d.isHoliday ? '휴일입니다' : undefined} />
      ) : (
        <>
          <Timeline d={d} />
          {!d.clockOut && <div className={s.muted}>근로시간은 퇴근 후에 계산됩니다</div>}
          <UnapprovedOtCta days={[d]} />
          <WorkTimeTable wt={d.workTime} />
        </>
      )}
    </div>
  );
}
