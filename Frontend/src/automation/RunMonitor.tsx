import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Skeleton } from 'antd';
import { Link } from 'react-router-dom';
import {
  isFinal, useCancelRun, useNodeCatalog, useRun, useRunSteps, useWorkflow,
  type ApprovalWaiting, type HitlWaiting, type RunDetail, type RunStep, type Waiting,
} from '@/api/ai';
import { useMyId } from '@/auth/session';
import { StatusBadge } from '@/ui/StatusBadge';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { AiMark } from '@/ui/primitives';
import { ConfirmAction } from '@/ui/ConfirmAction';
import { useNotify } from '@/ui/useNotify';
import { Icon } from '@/ui/Icon';
import { Check, X, Loader, UserCheck } from '@/ui/icons';
import { formatElapsed, formatTime, kst, serverUtc } from '@/format';
import { HitlPanel } from './HitlPanel';
import { usePreviewStore } from './previewStore';
import s from './automation.module.css';

const ms = (a: string, b: string | null) => (b ? kst(serverUtc(b)).diff(kst(serverUtc(a))) : null);

/** 최종 결과: run 상태 + 마지막 step branch(반려로 끝나도 SUCCEEDED, 요구사항 3.7) */
function Outcome({ run, steps }: { run: RunDetail; steps: RunStep[] }) {
  const last = steps[steps.length - 1];
  if (run.status === 'SUCCEEDED' && last && (last.branch === '반려' || last.branch === '회수')) {
    return <StatusBadge domain="RunOutcome" value="ENDED_REJECTED" />;
  }
  return <StatusBadge domain="RunStatus" value={run.status} />;
}

/** 실행된 단계 세로 타임라인. RunDetail에 실행 그래프가 없어 초안 그래프 캔버스는 그리지 않는다(gaps B11) */
function StepTimeline({ steps, running }: { steps: RunStep[]; running: boolean }) {
  const catalog = useNodeCatalog();
  return (
    <ol className={s.steps}>
      {steps.map((st) => {
        const node = catalog.data?.get(st.nodeType);
        const dur = ms(st.startedAt, st.finishedAt);
        const icon = st.status === 'SUCCEEDED' ? Check : st.status === 'FAILED' ? X : st.status === 'WAITING' ? UserCheck : Loader;
        return (
          <li key={st.id} className={[s.step, s[`st-${st.status}`]].join(' ')}>
            <span className={s.stepDot}><Icon as={icon} size={12} className={st.status === 'RUNNING' ? 'nx-spin' : undefined} /></span>
            <div className={s.stepBody}>
              <div className={s.stepHead}>
                <b>{node?.name ?? st.nodeType}</b>
                {node?.category === 'ai' && <AiMark label={st.nodeType === 'llm_verify' ? 'AI 검증' : 'AI 추출'} />}
                {st.status === 'WAITING' && <span className={s.here}>지금 여기</span>}
              </div>
              <div className={s.stepMeta}>
                <span className="nx-num">{dur != null ? formatElapsed(dur) : st.status === 'RUNNING' ? '실행 중' : '—'}</span>
                {st.branch && <span className={s.branch}>→ {st.branch}</span>}
                {st.error && <span className={s.err}>{st.error}</span>}
              </div>
            </div>
          </li>
        );
      })}
      {running && <li className={`${s.step} ${s.stepNext}`}><span className={s.stepDot} /><span className={s.muted}>다음 단계 준비 중…</span></li>}
    </ol>
  );
}

/** approval_draft 단계 출력 → 결재 문서 카드 */
function DocCard({ steps }: { steps: RunStep[] }) {
  const st = steps.find((x) => x.nodeType === 'approval_draft' && x.status === 'SUCCEEDED');
  const out = st?.output as { document_id?: number; doc_no?: string; title?: string } | null | undefined;
  if (!out?.document_id) return null;
  return (
    <div className={s.docCard} role="status">
      <b>결재 문서 생성됨</b> <span className="nx-mono">{out.doc_no}</span>
      {out.title && <span className={s.muted}>{out.title}</span>}
      <Link to={`/approvals/${out.document_id}`} style={{ marginLeft: 'auto' }}><Button size="small">문서 열기</Button></Link>
    </div>
  );
}

function Preview({ runId }: { runId: number }) {
  const list = usePreviewStore((st) => st.byRun[runId]);
  if (!list || list.length === 0) return <div className={s.noPreview}>원본은 업로드한 브라우저에서만 표시됩니다</div>;
  return (
    <div className={s.previewList}>
      {list.map((p) => p.type.startsWith('image/')
        ? <a key={p.url} href={p.url} target="_blank" rel="noreferrer"><img src={p.url} alt={p.name} className={s.previewImg} /></a>
        : <div key={p.url} className={s.muted}>{p.name}</div>)}
    </div>
  );
}

/** 실행 화면(설계 6.5 Run Theater, 요구사항 3.7). 사원 실행과 관리자 테스트 실행이 같이 쓴다 */
export function RunMonitor({ runId }: { runId: number }) {
  const meId = useMyId();
  const notify = useNotify();
  const [boostUntil, setBoostUntil] = useState(0);
  const boost = Date.now() < boostUntil;
  const run = useRun(runId, boost);
  const steps = useRunSteps(runId, run.data?.status, boost);
  const wf = useWorkflow(run.data?.workflowId ?? null);
  const cancel = useCancelRun(runId);

  // 끝나면 한 번 알린다(처음부터 끝난 실행을 연 경우는 제외)
  const prev = useRef(run.data?.status);
  useEffect(() => {
    const cur = run.data?.status;
    if (prev.current && cur && prev.current !== cur && isFinal(cur)) {
      if (cur === 'SUCCEEDED') notify.success(`run #${runId} 실행이 끝났습니다`);
      if (cur === 'FAILED') notify.info(`run #${runId} 실행이 실패했습니다`);
    }
    prev.current = cur;
  }, [run.data?.status, runId, notify]);

  if (run.isPending) return <Skeleton active={false} paragraph={{ rows: 8 }} />;
  if (run.isError) return <ErrorPanel error={run.error} onRetry={() => run.refetch()} />;
  const r = run.data;
  const list = steps.data ?? [];
  const waitingStep = list.find((x) => x.status === 'WAITING');
  const waiting = (waitingStep?.output as { waiting_for?: Waiting } | null)?.waiting_for;
  const isRequester = meId != null && r.requestedBy === String(meId);
  const cancellable = !isFinal(r.status) && waiting?.type !== 'approval_wait';

  return (
    <div className={s.monitor}>
      <div className={s.monHead}>
        <h2 className={s.title}>{wf.data?.name ?? `워크플로 #${r.workflowId}`}</h2>
        <span className="nx-mono">run #{r.id}</span>
        {r.isTest && <span className={s.tag}>테스트 실행</span>}
        <span className={s.muted}>시작 <span className="nx-num">{formatTime(serverUtc(r.startedAt ?? r.createdAt), true)}</span></span>
        <span className={s.spacer} />
        <Outcome run={r} steps={list} />
        {cancellable && (
          <ConfirmAction
            title={`run #${r.id} 실행을 취소합니다`}
            confirmLabel="실행 취소"
            danger
            onConfirm={() => cancel.mutateAsync().then(() => notify.success('실행을 취소했습니다'), (e) => notify.error(e, '취소하지 못했습니다'))}
            trigger={(open) => <Button type="text" onClick={open}>실행 취소</Button>}
          >
            진행 중인 단계가 끝나면 멈추고, 이미 끝난 단계(파일 업로드·추출 등)는 되돌리지 않습니다.
          </ConfirmAction>
        )}
      </div>

      <div className={s.monGrid}>
        <section className={s.panel}>
          <h3 className={s.h3}>진행 단계</h3>
          {steps.isError ? <ErrorPanel error={steps.error} onRetry={() => steps.refetch()} />
            : !steps.data ? <Skeleton active={false} paragraph={{ rows: 4 }} />
            : <StepTimeline steps={list} running={r.status === 'RUNNING' || r.status === 'QUEUED'} />}
          {r.status === 'FAILED' && r.error && <Alert type="error" showIcon message="실행이 실패했습니다" description={r.error} />}
          <DocCard steps={list} />
        </section>

        <section className={s.panel}>
          {waiting?.type === 'human_confirm' && waitingStep ? (
            <HitlPanel key={waitingStep.id} runId={r.id} waiting={waiting as HitlWaiting} canResume={isRequester}
                       onResumed={() => setBoostUntil(Date.now() + 30_000)} />
          ) : waiting?.type === 'approval_wait' ? (
            <div className={s.waitApproval}>
              <b>결재 대기 중</b> <span className="nx-mono">{(waiting as ApprovalWaiting).doc_no}</span>
              <span className={s.muted}>결재가 끝나면 자동으로 이어서 진행됩니다</span>
              <Link to={`/approvals/${(waiting as ApprovalWaiting).document_id}`}><Button size="small">문서 열기</Button></Link>
            </div>
          ) : (
            <>
              <h3 className={s.h3}>원본</h3>
              <Preview runId={r.id} />
            </>
          )}
        </section>
      </div>
    </div>
  );
}
