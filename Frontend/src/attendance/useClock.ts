import { useEffect, useState } from 'react';
import { useClockAction, useToday, type AttendanceStatus } from '@/api/attendance';
import { kst, nowKst } from '@/format';
import { useNotify } from '@/ui/useNotify';

export type ClockPhase = 'before' | 'working' | 'after';

/** 출퇴근 위젯 규칙(요구사항 3.2)을 한 곳에서 계산한다. 탑바 ClockChip과 홈 카드가 같이 쓴다. */
export function clockPhase(t: AttendanceStatus | undefined): ClockPhase {
  if (!t?.clockIn) return 'before';
  return t.clockOut ? 'after' : 'working';
}

/** 1분마다 다시 그려 경과 시간을 갱신한다. */
function useMinuteTick() {
  const [, set] = useState(0);
  useEffect(() => {
    const id = setInterval(() => set((n) => n + 1), 60_000);
    return () => clearInterval(id);
  }, []);
}

export function useClock() {
  const today = useToday();
  const action = useClockAction();
  const notify = useNotify();
  useMinuteTick();

  const t = today.data;
  const phase = clockPhase(t);
  const elapsedMin = t?.clockIn ? Math.max(0, (t.clockOut ? kst(t.clockOut) : nowKst()).diff(kst(t.clockIn), 'minute')) : 0;
  // 퇴근 후 ableStatusList에 WORKING이 있으면 퇴근 취소 가능(퇴근 후 2시간 이내)
  const canUndoClockOut = phase === 'after' && !!t?.ableStatusList.some((s) => s.statusCode === 'WORKING');
  // 근무 중 전환 버튼: 현재 상태는 제외
  const switchable = phase === 'working' ? (t?.ableStatusList ?? []).filter((s) => s.statusCode !== t?.statusCode) : [];

  const run = (a: Parameters<typeof action.mutate>[0], done: string) =>
    action.mutate(a, {
      onSuccess: () => notify.success(done),
      onError: (e) => notify.error(e),
    });

  return {
    query: today,
    today: t,
    phase,
    elapsedMin,
    canUndoClockOut,
    switchable,
    pending: action.isPending,
    clockIn: () => run({ kind: 'in' }, '출근했습니다'),
    clockOut: () => run({ kind: 'out' }, '퇴근했습니다'),
    changeStatus: (statusCode: string, name: string) => run({ kind: 'status', statusCode }, `${name}(으)로 바꿨습니다`),
    undoClockOut: () => run({ kind: 'status', statusCode: 'WORKING' }, '퇴근을 취소했습니다'),
  };
}
