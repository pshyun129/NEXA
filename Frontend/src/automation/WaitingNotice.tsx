import { useState } from 'react';
import { Button } from 'antd';
import { useMyPendingRuns, useResumeRun, useRunSteps, useWorkflowNames, type RunSummary, type Waiting } from '@/api/ai';
import { useNotify } from '@/ui/useNotify';
import { Icon } from '@/ui/Icon';
import { Sparkles, X } from '@/ui/icons';
import { formatRelative, serverUtc } from '@/format';
import { useAutomationUi } from './automationStore';
import s from './hub.module.css';

const DISMISS_KEY = 'nx.dismissedWaiting';
const readDismissed = (): number[] => {
  try { return JSON.parse(sessionStorage.getItem(DISMISS_KEY) ?? '[]') as number[]; } catch { return []; }
};

/** 알림 한 건: 사람 확인이면 사유와 [확인하고 계속], 결재 대기면 알리지 않는다(결재 버튼이 맡는다) */
function NoticeItem({ run, name, onDismiss }: { run: RunSummary; name?: string; onDismiss: () => void }) {
  const steps = useRunSteps(run.id, run.status);
  const resume = useResumeRun(run.id);
  const openHub = useAutomationUi((st) => st.openHub);
  const notify = useNotify();
  const waiting = (steps.data ?? []).find((x) => x.status === 'WAITING');
  const w = (waiting?.output as { waiting_for?: Waiting } | null)?.waiting_for;
  if (!steps.data || w?.type === 'approval_wait') return null;
  const reasons = w?.type === 'human_confirm' ? w.reasons : [];

  return (
    <div className={s.notice} role="alert">
      <div className={s.noticeHead}>
        <span className={s.noticeIcon}><Icon as={Sparkles} size={16} /></span>
        <div className={s.noticeTitle}>
          <strong>확인이 필요해요</strong>
          <span>{name ?? `워크플로 #${run.workflowId}`} · run #{run.id} · {formatRelative(serverUtc(run.createdAt))}</span>
        </div>
        <button type="button" className={s.miniBtn} onClick={onDismiss} aria-label="알림 닫기"><Icon as={X} size={16} /></button>
      </div>
      <p className={s.noticeMsg}>{w?.message ?? 'AI가 읽은 값을 확인해 주세요.'}</p>
      {reasons.length > 0 && <ul className={s.noticeReasons}>{reasons.slice(0, 2).map((r) => <li key={r}>{r}</li>)}</ul>}
      <div className={s.noticeActions}>
        <Button size="small" onClick={() => openHub({ kind: 'run', runId: run.id })}>자세히 보기</Button>
        <Button size="small" type="primary" loading={resume.isPending}
                onClick={() => resume.mutate({ approved: true }, {
                  onSuccess: () => notify.success('확인하고 계속 진행합니다'),
                  onError: (e) => notify.error(e, '진행하지 못했습니다'),
                })}>
          확인하고 계속
        </Button>
      </div>
    </div>
  );
}

/**
 * 워크플로 중간에 사람 확인이 필요하면 오른쪽 위에 작은 알림 창을 띄운다(상단 [확인 대기] 버튼 대신).
 * [확인하고 계속]은 AI가 읽은 값 그대로 승인한다. 값을 고치려면 [자세히 보기]에서 실행 화면을 연다.
 */
export function WaitingNotice() {
  const pending = useMyPendingRuns();
  const hubOpen = useAutomationUi((st) => st.open);
  const mini = useAutomationUi((st) => st.mini);
  const [dismissed, setDismissed] = useState<number[]>(readDismissed);
  const list = (pending.data ?? []).filter((r) => !dismissed.includes(r.id) && r.id !== mini?.runId).slice(0, 2);
  const names = useWorkflowNames(list.map((r) => r.workflowId));
  if (hubOpen || list.length === 0) return null;
  const dismiss = (id: number) => {
    const next = [...dismissed, id];
    setDismissed(next);
    try { sessionStorage.setItem(DISMISS_KEY, JSON.stringify(next)); } catch { /* noop */ }
  };
  return (
    <>
      {list.map((r) => <NoticeItem key={r.id} run={r} name={names[r.workflowId]} onDismiss={() => dismiss(r.id)} />)}
    </>
  );
}
