import { useState } from 'react';
import { Drawer, Skeleton } from 'antd';
import { useHolidays, useMonthly, type AttendanceDaySummary, type Holiday, type MonthlyAttendance } from '@/api/attendance';
import { formatDate, formatDurationShort, formatTime, kst, nowKst } from '@/format';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { StatusBadge } from '@/ui/StatusBadge';
import { leavePortionLabel } from '@/status/labels';
import { DailyDetail } from './DailyDetail';
import { UnapprovedOtCta } from './AnomalyChips';
import s from './attendance.module.css';

const WEEKDAYS = ['월', '화', '수', '목', '금', '토', '일'];

function MonthlySummary({ m }: { m: MonthlyAttendance }) {
  const sm = m.summary;
  return (
    <div className={s.summary}>
      <span className={`${s.kpi} nx-num`}>{formatDurationShort(sm.totalMin)}</span>
      <span>소정 <b className="nx-num">{formatDurationShort(sm.stdMin)}</b></span>
      <span>잔여 소정 <b className="nx-num">{formatDurationShort(sm.remainStdMin)}</b></span>
      <span>근무일 <b className="nx-num">{sm.workDays}일</b> (남은 {sm.remainWorkDays}일)</span>
      {sm.approvedOtMin > 0 && <span>승인 초과 <b className="nx-num">{formatDurationShort(sm.approvedOtMin)}</b></span>}
      {sm.unapprovedOtMin > 0 && <span className={s.danger}>미승인 초과 {formatDurationShort(sm.unapprovedOtMin)}</span>}
    </div>
  );
}

function DayCell({ d, holiday, today, onOpen }: { d: AttendanceDaySummary; holiday?: Holiday; today: string; onOpen: () => void }) {
  const dt = kst(d.workDate);
  const weekend = dt.isoWeekday() >= 6;
  const label = [formatDate(d.workDate), holiday?.name, d.workTime.totalMin ? formatDurationShort(d.workTime.totalMin) : null]
    .filter(Boolean).join(', ');
  return (
    <button type="button" onClick={onOpen} aria-label={label}
            className={[s.calCell, d.isHoliday || weekend ? s.calHoliday : '', d.workDate === today ? s.calToday : ''].join(' ')}>
      <span className={s.calHead}>
        <span className={`${s.calDate} nx-num`}>{dt.date()}</span>
        {holiday && <span className={s.calHolidayName}>{holiday.name}</span>}
      </span>
      {d.clockIn && (
        <span className={`${s.calTime} nx-num`}>
          {formatTime(d.clockIn)}~{d.clockOut ? formatTime(d.clockOut) : d.workDate === today ? '근무 중' : '—'}
        </span>
      )}
      {d.workTime.totalMin > 0 && <span className={`${s.calTotal} nx-num`}>{formatDurationShort(d.workTime.totalMin)}</span>}
      <span className={s.calTags}>
        {d.leaves.map((l, i) => <span key={i} className={s.tag}>{l.leaveTypeName} {leavePortionLabel(l.portion)}</span>)}
        {d.anomalies.map((a) => <StatusBadge key={a} domain="AnomalyType" value={a} />)}
      </span>
    </button>
  );
}

/**
 * 월간 캘린더(P1, 요구사항 3.2). 월~일 7칸, 공휴일 이름은 /holidays에서 붙인다.
 * 칸을 누르면 일간 Drawer(근태이상 → 신청 바로가기는 일간 상세에 있다).
 */
export function MonthlyView({ month }: { month: string }) {
  const q = useMonthly(month);
  const first = kst(`${month}-01`);
  const hol = useHolidays(first.format('YYYY-MM-DD'), first.endOf('month').format('YYYY-MM-DD'));
  const [open, setOpen] = useState<string | null>(null);
  if (q.isError) return <ErrorPanel error={q.error} onRetry={() => q.refetch()} />;
  if (!q.data) return <Skeleton active={false} paragraph={{ rows: 10 }} />;

  const today = nowKst().format('YYYY-MM-DD');
  const holidays = new Map((hol.data ?? []).map((h) => [h.holidayDate, h]));
  const lead = first.isoWeekday() - 1;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <MonthlySummary m={q.data} />
      <UnapprovedOtCta days={q.data.days} />
      <div className={s.calendar} role="grid" aria-label={`${first.format('YYYY년 M월')} 근태`}>
        {WEEKDAYS.map((w) => <div key={w} className={s.calWeekday} role="columnheader">{w}</div>)}
        {Array.from({ length: lead }, (_, i) => <div key={`pad${i}`} />)}
        {q.data.days.map((d) => (
          <DayCell key={d.workDate} d={d} holiday={holidays.get(d.workDate)} today={today} onOpen={() => setOpen(d.workDate)} />
        ))}
      </div>
      <Drawer open={open != null} onClose={() => setOpen(null)} width={560} title={open ? formatDate(open) : ''} destroyOnHidden>
        {open && <DailyDetail date={open} />}
      </Drawer>
    </div>
  );
}
