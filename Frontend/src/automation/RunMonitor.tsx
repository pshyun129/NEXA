import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Skeleton } from 'antd';
import { Link } from 'react-router-dom';
import {
  isFinal, runBadgeValue, runEndedRejected, runFileDownloadUrl, runInputFiles, useCancelRun, useNodeCatalog, useRun, useRunSteps, useWorkflow,
  type ApprovalWaiting, type HitlWaiting, type RunDetail, type RunInputFile, type RunStep, type Waiting,
} from '@/api/ai';
import { useMyId } from '@/auth/session';
import { StatusBadge } from '@/ui/StatusBadge';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { AiMark } from '@/ui/primitives';
import { ConfirmAction } from '@/ui/ConfirmAction';
import { useNotify } from '@/ui/useNotify';
import { Icon } from '@/ui/Icon';
import { Check, Eye, FileText, Sheet, X, Loader, UserCheck } from '@/ui/icons';
import { formatElapsed, formatTime, kst, serverUtc } from '@/format';
import type { Graph } from '@/api/builder';
import { HitlPanel } from './HitlPanel';
import { RunCanvas } from './RunCanvas';
import { FilePreview, previewKind, type PreviewTarget } from './FilePreview';
import { usePreviewStore } from './previewStore';
import s from './automation.module.css';

const ms = (a: string, b: string | null) => (b ? kst(serverUtc(b)).diff(kst(serverUtc(a))) : null);

/** 최종 결과: 반려로 끝나도 run은 SUCCEEDED라(요구사항 3.7) 결재 대기·사람 확인 단계의 '반려/회수'를 본다 */
function Outcome({ run, steps }: { run: RunDetail; steps: RunStep[] }) {
  if (runEndedRejected(run.status, steps)) return <StatusBadge domain="RunOutcome" value="ENDED_REJECTED" />;
  return <StatusBadge domain="RunStatus" value={runBadgeValue(run)} />;
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

/** 원본 파일 하나. 이미지는 바로 보여 주고, 그 밖의 형식(PDF·엑셀 등)은 [미리보기] 후 내려받기 */
function PreviewItem({ f, onPreview }: { f: RunInputFile; onPreview: (t: PreviewTarget) => void }) {
  const local = usePreviewStore((st) => st.byFile[f.file_id]);
  const [failed, setFailed] = useState(false);
  const url = local?.url ?? runFileDownloadUrl(f.file_id);
  const type = f.content_type ?? local?.type ?? '';
  if (failed) return <div className={s.muted}>{f.name} · 원본을 불러오지 못했습니다</div>;
  if (type.startsWith('image/')) {
    return (
      <a href={url} target="_blank" rel="noreferrer">
        <img src={url} alt={f.name} className={s.previewImg} onError={() => setFailed(true)} />
      </a>
    );
  }
  return (
    <div className={s.fileRow}>
      <Icon as={FileText} size={16} />
      <span className={s.fileName}>{f.name}</span>
      {previewKind(f.name, type) !== 'other' && (
        <Button size="small" icon={<Icon as={Eye} size={16} />} onClick={() => onPreview({ fileId: f.file_id, name: f.name, contentType: type, localUrl: local?.url })}>미리보기</Button>
      )}
      <a href={url} download={f.name}><Button size="small" type="text">내려받기</Button></a>
    </div>
  );
}

/** 실행이 만든 결과 파일(엑셀 작성 등 출력에 file_id가 있는 단계) → 미리보기 후 내려받기 */
function ResultFiles({ steps, onPreview }: { steps: RunStep[]; onPreview: (t: PreviewTarget) => void }) {
  const files = steps.flatMap((st) => {
    const o = st.output as { file_id?: number; file_name?: string; row_count?: number } | null;
    return st.status === 'SUCCEEDED' && o?.file_id != null && o.file_name ? [{ id: o.file_id, name: o.file_name, rows: o.row_count }] : [];
  });
  if (files.length === 0) return null;
  return (
    <div className={s.results}>
      <h3 className={s.h3}>결과 파일</h3>
      {files.map((f) => (
        <div key={f.id} className={s.resultFile}>
          <span className={s.resultIcon}><Icon as={previewKind(f.name) === 'xlsx' ? Sheet : FileText} size={20} /></span>
          <span className={s.resultMain}>
            <b>{f.name}</b>
            {f.rows != null && <span className={s.muted}>{f.rows}행</span>}
          </span>
          <Button size="small" type="primary" icon={<Icon as={Eye} size={16} />} onClick={() => onPreview({ fileId: f.id, name: f.name })}>미리보기</Button>
          <a href={runFileDownloadUrl(f.id)} download={f.name}><Button size="small">내려받기</Button></a>
        </div>
      ))}
    </div>
  );
}

/** 원본(설계 6.5). 서버는 업로더·요청자에게만 내려준다 → 다른 사람(관리자 조회 등)에게는 안내만 */
function Preview({ run, isRequester, onPreview }: { run: RunDetail; isRequester: boolean; onPreview: (t: PreviewTarget) => void }) {
  const files = runInputFiles(run);
  if (files.length === 0) return <div className={s.noPreview}>첨부한 원본이 없습니다</div>;
  if (!isRequester) return <div className={s.noPreview}>원본은 실행한 사람만 볼 수 있습니다</div>;
  return <div className={s.previewList}>{files.map((f) => <PreviewItem key={f.file_id} f={f} onPreview={onPreview} />)}</div>;
}

/**
 * 실행 화면(설계 6.5 Run Theater, 요구사항 3.7). 사원 실행과 관리자 테스트 실행이 같이 쓴다.
 * canvas: 위쪽에 빌더와 같은 노드 캔버스로 진행을 크게 보여 준다(빌더 테스트 실행 창은 빌더 캔버스가 이미 보여 줘서 끈다)
 */
export function RunMonitor({ runId, canvas = true }: { runId: number; canvas?: boolean }) {
  const [preview, setPreview] = useState<PreviewTarget | null>(null);
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
  // 실행 당시 그래프(서버가 내려 주면) → 없으면 워크플로 초안. 초안이 실행 뒤 바뀌었으면 단계 목록이 정답이다
  const graph = r.graph ?? (wf.data as { draftGraph?: Graph } | undefined)?.draftGraph;
  const drifted = !!graph && list.some((st) => !graph.nodes.some((n) => n.id === st.nodeId));
  const done = list.filter((x) => x.status === 'SUCCEEDED').length;
  const total = Math.max(graph?.nodes.length ?? 0, list.length, 1);
  const pct = r.status === 'SUCCEEDED' ? 100 : Math.round((done / total) * 100);

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

      {canvas && graph && (
        <section className={s.stage}>
          <RunCanvas graph={graph} steps={list} status={r.status}
                     note={drifted ? '워크플로가 실행 뒤 바뀌어 일부 단계는 아래 진행 단계에서 확인하세요' : undefined} />
          <div className={s.stageBar}>
            <div className={s.stageProgress}><span style={{ width: `${pct}%` }} className={r.status === 'FAILED' ? s.stageFail : ''} /></div>
            <span className="nx-num">{done}/{total} 단계</span>
          </div>
        </section>
      )}

      <div className={s.monGrid}>
        <section className={s.panel}>
          <h3 className={s.h3}>진행 단계</h3>
          {steps.isError ? <ErrorPanel error={steps.error} onRetry={() => steps.refetch()} />
            : !steps.data ? <Skeleton active={false} paragraph={{ rows: 4 }} />
            : <StepTimeline steps={list} running={r.status === 'RUNNING' || r.status === 'QUEUED'} />}
          {r.status === 'FAILED' && r.error && <Alert type="error" showIcon message="실행이 실패했습니다" description={r.error} />}
          <DocCard steps={list} />
          <ResultFiles steps={list} onPreview={setPreview} />
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
              <Preview run={r} isRequester={isRequester} onPreview={setPreview} />
            </>
          )}
        </section>
      </div>
      <FilePreview target={preview} onClose={() => setPreview(null)} />
    </div>
  );
}
