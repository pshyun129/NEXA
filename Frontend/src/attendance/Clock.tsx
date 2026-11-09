import { Button, Popover } from 'antd';
import { Link } from 'react-router-dom';
import { DotChip } from '@/ui/primitives';
import { formatDurationShort, formatDuration, formatTime } from '@/format';
import { workStatusTone } from '@/status/labels';
import { useClock } from './useClock';
import { newRequestHref } from './requests/links';
import s from './attendance.module.css';

type Clock = ReturnType<typeof useClock>;

/** 상태별 버튼 묶음. 탑바 팝오버와 홈 카드가 같은 규칙을 쓴다(요구사항 3.2). */
function ClockButtons({ c, size }: { c: Clock; size: 'small' | 'middle' }) {
  if (c.phase === 'before') {
    return <Button type="primary" size={size} loading={c.pending} onClick={c.clockIn}>출근하기</Button>;
  }
  if (c.phase === 'working') {
    return (
      <div className={s.btnRow}>
        {c.switchable.map((st) => (
          <Button key={st.statusCode} size={size} disabled={c.pending} onClick={() => c.changeStatus(st.statusCode, st.statusName)}>
            {st.statusName}
          </Button>
        ))}
        <Button type="primary" size={size} loading={c.pending} onClick={c.clockOut}>퇴근</Button>
      </div>
    );
  }
  if (c.canUndoClockOut) {
    return <Button size={size} loading={c.pending} onClick={c.undoClockOut}>퇴근 취소</Button>;
  }
  // 퇴근 2시간 이후에는 변경 불가 → 근태 신청(TIME_FIX)으로 안내
  return <Link to={newRequestHref({ type: 'TIME_FIX', date: c.today?.workDate })}>수정은 근태 신청 →</Link>;
}

/** 탑바 ClockChip(설계 5장). 출근 전에는 칩 자리에 "출근하기" 버튼을 둔다. */
export function ClockChip() {
  const c = useClock();
  if (c.query.isError || (c.query.isPending && !c.today)) return null;
  if (c.phase === 'before') {
    return <Button type="primary" size="small" loading={c.pending} onClick={c.clockIn}>출근하기</Button>;
  }
  const t = c.today!;
  const label = c.phase === 'working'
    ? <>{t.statusName} <span className="nx-num">{formatTime(t.clockIn)}~ · {formatDurationShort(c.elapsedMin)}</span></>
    : <>퇴근 <span className="nx-num">{formatTime(t.clockOut)} · {formatDurationShort(c.elapsedMin)}</span></>;
  return (
    <Popover
      trigger="click"
      placement="bottomRight"
      content={
        <div className={s.popover}>
          <div className={s.popoverMeta}>
            출근 <b className="nx-num">{formatTime(t.clockIn)}</b>
            {t.clockOut && <> · 퇴근 <b className="nx-num">{formatTime(t.clockOut)}</b></>}
          </div>
          <ClockButtons c={c} size="small" />
        </div>
      }
    >
      <button type="button" className={s.chip} aria-label="근무 상태">
        <DotChip tone={c.phase === 'after' ? 'outline' : workStatusTone(t.statusCode)}>{label}</DotChip>
      </button>
    </Popover>
  );
}

/** 홈 "오늘 근무" 카드 본문(설계 6.1). */
export function ClockPanel() {
  const c = useClock();
  const t = c.today;
  if (!t) return null;
  if (c.phase === 'before') {
    return (
      <div className={s.panelBefore}>
        <div className={s.panelSub}>아직 출근 기록이 없습니다</div>
        <Button type="primary" size="large" loading={c.pending} onClick={c.clockIn}>출근하기</Button>
      </div>
    );
  }
  return (
    <div className={s.panel}>
      <DotChip tone={c.phase === 'after' ? 'outline' : workStatusTone(t.statusCode)}>
        {c.phase === 'after' ? '퇴근' : t.statusName}
      </DotChip>
      <div className={s.panelTimes}>
        <span>출근 <b className="nx-num">{formatTime(t.clockIn)}</b></span>
        {t.clockOut && <span>퇴근 <b className="nx-num">{formatTime(t.clockOut)}</b></span>}
      </div>
      <div className={s.panelSub}>{c.phase === 'after' ? '출근~퇴근' : '경과'} {formatDuration(c.elapsedMin)}</div>
      <ClockButtons c={c} size="middle" />
    </div>
  );
}
