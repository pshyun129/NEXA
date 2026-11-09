import { useState } from 'react';
import { Drawer, Skeleton, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useWeekly, type AttendanceDaySummary, type WeeklyAttendance } from '@/api/attendance';
import { formatDate, formatDateShort, formatDuration, formatDurationShort, formatTime, kst, nowKst } from '@/format';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { leavePortionLabel } from '@/status/labels';
import { DailyDetail } from './DailyDetail';
import { AnomalyChips, UnapprovedOtCta } from './AnomalyChips';
import s from './attendance.module.css';

/** 날짜가 속한 주의 월요일(YYYY-MM-DD). 주간 조회 키로 쓴다. */
export const mondayOf = (d: string | ReturnType<typeof nowKst>) => kst(d).startOf('isoWeek').format('YYYY-MM-DD');

/** 주 누적 근로: 총/최대 막대 + 소정 눈금(설계 6.1). 값은 모두 주간 API summary. */
export function WeeklyGauge({ w }: { w: WeeklyAttendance }) {
  const { totalMin, stdMin, maxMin } = w.summary;
  const pct = (m: number) => `${Math.min(100, maxMin > 0 ? (m / maxMin) * 100 : 0)}%`;
  return (
    <div>
      <div className={s.gauge} role="img"
           aria-label={`이번 주 ${formatDuration(totalMin)}, 소정 ${formatDuration(stdMin)}, 최대 ${formatDuration(maxMin)}`}>
        <div className={[s.gaugeFill, totalMin > stdMin ? s.gaugeOver : ''].join(' ')} style={{ width: pct(totalMin) }} />
        <div className={s.gaugeMark} style={{ left: pct(stdMin) }} title="소정" />
      </div>
      <div className={s.gaugeLegend}>
        <span>0</span>
        <span>소정 {formatDurationShort(stdMin)} · 최대 {formatDurationShort(maxMin)}</span>
      </div>
    </div>
  );
}

export function WeeklySummary({ w }: { w: WeeklyAttendance }) {
  const sm = w.summary;
  return (
    <div className={s.summary}>
      <span className={`${s.kpi} nx-num`}>{formatDurationShort(sm.totalMin)}</span>
      <span>소정 <b className="nx-num">{formatDurationShort(sm.stdMin)}</b></span>
      <span>최대 <b className="nx-num">{formatDurationShort(sm.maxMin)}</b></span>
      <span>잔여 소정 <b className="nx-num">{formatDurationShort(sm.remainStdMin)}</b></span>
      {sm.approvedOtMin > 0 && <span>승인 초과 <b className="nx-num">{formatDurationShort(sm.approvedOtMin)}</b></span>}
      {sm.unapprovedOtMin > 0 && <span className={s.danger}>미승인 초과 {formatDurationShort(sm.unapprovedOtMin)}</span>}
    </div>
  );
}

/**
 * 주간 표(설계 6.4). 주간 응답에는 상태 구간(records)이 없어서 막대를 근사해 그리지 않는다.
 * 구간 타임라인은 행 클릭 Drawer(일간 API)에서만 그린다. (S15P3log/frontend-backend-gaps.md B3)
 */
export function WeeklyTable({ w }: { w: WeeklyAttendance }) {
  const [open, setOpen] = useState<string | null>(null);
  const today = nowKst().format('YYYY-MM-DD');
  const cols: ColumnsType<AttendanceDaySummary> = [
    {
      title: '날짜', dataIndex: 'workDate', width: 120,
      render: (v: string) => <span className={[s.dayCell, v === today ? s.dayToday : ''].join(' ')}>{formatDateShort(v)}</span>,
    },
    { title: '출근', dataIndex: 'clockIn', width: 80, render: (v) => <span className="nx-num">{formatTime(v)}</span> },
    {
      title: '퇴근', dataIndex: 'clockOut', width: 80,
      render: (v, r) => <span className="nx-num">{r.clockIn && !v ? (r.workDate === today ? '근무 중' : '—') : formatTime(v)}</span>,
    },
    { title: '총', key: 'total', width: 90, align: 'right', render: (_, r) => <span className="nx-num">{r.workTime.totalMin ? formatDurationShort(r.workTime.totalMin) : '—'}</span> },
    { title: '연장', key: 'ot', width: 80, align: 'right', render: (_, r) => <span className="nx-num">{r.workTime.otExtendedMin ? formatDurationShort(r.workTime.otExtendedMin) : '—'}</span> },
    { title: '휴일', key: 'hol', width: 80, align: 'right', render: (_, r) => <span className="nx-num">{r.workTime.otHolidayMin ? formatDurationShort(r.workTime.otHolidayMin) : '—'}</span> },
    {
      title: '미승인 초과', key: 'unap', width: 100, align: 'right',
      render: (_, r) => r.workTime.unapprovedOtMin
        ? <span className={`${s.danger} nx-num`}>{formatDurationShort(r.workTime.unapprovedOtMin)}</span>
        : <span className="nx-num">—</span>,
    },
    {
      title: '휴가·이상', key: 'tags',
      render: (_, r) => (
        <div className={s.cellTags}>
          {r.isHoliday && <span className={s.tag}>휴일</span>}
          {r.leaves.map((l, i) => <span key={i} className={s.tag}>{l.leaveTypeName} {leavePortionLabel(l.portion)}</span>)}
          <AnomalyChips day={r} />
        </div>
      ),
    },
  ];
  return (
    <>
      <Table<AttendanceDaySummary>
        rowKey="workDate"
        size="middle"
        pagination={false}
        columns={cols}
        dataSource={w.days}
        rowClassName={(r) => (r.isHoliday ? s.rowHoliday : '')}
        onRow={(r) => ({
          onClick: () => setOpen(r.workDate),
          onKeyDown: (e) => { if (e.key === 'Enter') setOpen(r.workDate); },
          tabIndex: 0,
          style: { cursor: 'pointer' },
        })}
      />
      <Drawer open={open != null} onClose={() => setOpen(null)} width={560} title={open ? formatDate(open) : ''} destroyOnHidden>
        {open && <DailyDetail date={open} />}
      </Drawer>
    </>
  );
}

export function WeeklyView({ monday }: { monday: string }) {
  const q = useWeekly(monday);
  if (q.isError) return <ErrorPanel error={q.error} onRetry={() => q.refetch()} />;
  if (!q.data) return <Skeleton active={false} paragraph={{ rows: 8 }} />;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div>
        <WeeklySummary w={q.data} />
        <WeeklyGauge w={q.data} />
      </div>
      <UnapprovedOtCta days={q.data.days} />
      <WeeklyTable w={q.data} />
    </div>
  );
}
