import { useEffect, useMemo, useRef } from 'react';
import { Button } from 'antd';
import { isFinal, runEndedRejected, useNodeCatalog, useRun, useRunSteps, useWorkflow, type RunStep } from '@/api/ai';
import type { Graph } from '@/api/builder';
import { lookOf, TONE_COLOR, type NodeCategory, type NodeRunState } from '@/flow/FlowNode';
import { Illustration } from '@/flow/Illustration';
import { Icon } from '@/ui/Icon';
import { Expand, X } from '@/ui/icons';
import { useNotify } from '@/ui/useNotify';
import { serverUtc } from '@/format';
import { useAutomationUi } from './automationStore';
import s from './hub.module.css';

/** 시작 노드부터 연결 순서대로 늘어놓는다(갈래는 먼저 만난 순서). 연결 안 된 노드는 뒤에 붙인다 */
function orderNodes(g: Graph | undefined): Graph['nodes'] {
  if (!g) return [];
  const inDeg = new Map(g.nodes.map((n) => [n.id, 0]));
  for (const e of g.edges) inDeg.set(e.target, (inDeg.get(e.target) ?? 0) + 1);
  const queue = g.nodes.filter((n) => (inDeg.get(n.id) ?? 0) === 0).sort((a, b) => a.position.x - b.position.x).map((n) => n.id);
  const seen = new Set<string>();
  const out: string[] = [];
  while (queue.length) {
    const id = queue.shift()!;
    if (seen.has(id)) continue;
    seen.add(id);
    out.push(id);
    g.edges.filter((e) => e.source === id).forEach((e) => queue.push(e.target));
  }
  g.nodes.forEach((n) => { if (!seen.has(n.id)) out.push(n.id); });
  return out.map((id) => g.nodes.find((n) => n.id === id)!);
}

const STATE_TEXT: Record<NodeRunState, string> = {
  idle: '대기', QUEUED: '대기', RUNNING: '실행 중', WAITING: '확인 대기', SUCCEEDED: '완료', FAILED: '실패', SKIPPED: '건너뜀',
};

/** 실행 중 단계 한 줄 설명 */
function stepLine(st: RunStep | undefined, name: string) {
  if (!st) return '첫 단계를 준비하고 있어요…';
  if (st.status === 'RUNNING') return `${name} 단계를 처리하고 있어요…`;
  if (st.status === 'WAITING') return `${name}: 확인이 필요해요`;
  if (st.status === 'FAILED') return `${name}: ${st.error ?? '실패했어요'}`;
  return `${name} 완료${st.branch ? ` → ${st.branch}` : ''}`;
}

/**
 * 오른쪽 위 작은 진행 창. 실행을 시작하면 큰 모달 대신 이 창이 뜨고, 빌더 캔버스와 같은 노드 그림·움직임으로
 * 지금 어느 단계인지 보여 준다. 끝나면 결과를 알리고, 확인이 필요하면 [확인하기]로 실행 화면을 연다.
 */
export function MiniRunPanel() {
  const mini = useAutomationUi((st) => st.mini);
  const closeMini = useAutomationUi((st) => st.closeMini);
  const openHub = useAutomationUi((st) => st.openHub);
  if (!mini) return null;
  return <Panel key={mini.runId} runId={mini.runId} workflowId={mini.workflowId}
                onClose={closeMini} onExpand={() => openHub({ kind: 'run', runId: mini.runId })} />;
}

function Panel({ runId, workflowId, onClose, onExpand }: { runId: number; workflowId: number; onClose: () => void; onExpand: () => void }) {
  const run = useRun(runId);
  const steps = useRunSteps(runId, run.data?.status);
  const wf = useWorkflow(workflowId);
  const catalog = useNodeCatalog();
  const notify = useNotify();
  const graph = (wf.data as { draftGraph?: Graph } | undefined)?.draftGraph;
  const ordered = useMemo(() => orderNodes(graph), [graph]);
  const byNode = useMemo(() => new Map((steps.data ?? []).map((st) => [st.nodeId, st])), [steps.data]);
  const status = run.data?.status;
  const final = isFinal(status);
  const list = steps.data ?? [];
  const current = [...list].reverse().find((x) => x.status !== 'SUCCEEDED') ?? list[list.length - 1];
  const done = list.filter((x) => x.status === 'SUCCEEDED').length;
  const total = Math.max(ordered.length, list.length, 1);
  const pct = status === 'SUCCEEDED' ? 100 : Math.round((done / total) * 100);
  const start = run.data ? Date.parse(serverUtc(run.data.startedAt ?? run.data.createdAt)) : 0;
  const end = list.reduce((t, x) => Math.max(t, x.finishedAt ? Date.parse(serverUtc(x.finishedAt)) : 0), 0);
  const secs = start && end ? Math.max(0, (end - start) / 1000) : 0;

  // 끝나면 한 번 알린다
  const prev = useRef(status);
  useEffect(() => {
    if (prev.current && status && prev.current !== status && isFinal(status)) {
      if (status === 'SUCCEEDED') {
        if (runEndedRejected(status, steps.data)) notify.info('결재가 반려되어 자동화를 마쳤습니다', wf.data?.name);
        else notify.success('자동화 실행이 끝났습니다', wf.data?.name);
      }
      if (status === 'FAILED') notify.info('자동화 실행이 실패했습니다', '실행 화면에서 원인을 확인해 주세요');
    }
    prev.current = status;
  }, [status, notify, wf.data?.name, steps.data]);

  // 지금 단계가 보이도록 가로 스크롤을 옮긴다
  const trackRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    trackRef.current?.querySelector('[data-current="true"]')?.scrollIntoView({ behavior: 'smooth', inline: 'center', block: 'nearest' });
  }, [current?.id, current?.status]);

  const nodes = ordered.length ? ordered : list.map((st) => ({ id: st.nodeId, type: st.nodeType, position: { x: 0, y: 0 }, config: {} }));
  const curName = current ? catalog.data?.get(current.nodeType)?.name ?? current.nodeType : '';
  const rejected = runEndedRejected(status, list);
  // 결재 대기는 결재자를 기다린다: 요청자에게 [확인하기]를 띄우지 않고 결재함으로 안내한다
  const approvalWait = status === 'WAITING' && run.data?.waitingType === 'approval_wait';
  const tone = rejected ? 'wait' : status === 'SUCCEEDED' ? 'ok' : status === 'FAILED' ? 'err' : status === 'WAITING' ? 'wait' : 'run';
  const statusText = rejected ? '반려로 종료' : status === 'SUCCEEDED' ? '완료' : status === 'FAILED' ? '실패'
    : approvalWait ? '결재 대기' : status === 'WAITING' ? '확인 대기' : status === 'CANCELED' ? '취소됨' : '실행 중';

  return (
    <section className={s.mini} role="status" aria-live="polite" aria-label="자동화 진행 상황">
      <div className={s.miniHead}>
        <span className={`${s.miniDot} ${s[`dot-${tone}`]}`} />
        <div className={s.miniTitle}>
          <strong>{wf.data?.name ?? `워크플로 #${workflowId}`}</strong>
          <span>run #{runId} · {statusText}</span>
        </div>
        <button type="button" className={s.miniBtn} onClick={onExpand} aria-label="실행 화면 크게 보기"><Icon as={Expand} size={16} /></button>
        <button type="button" className={s.miniBtn} onClick={onClose} aria-label="진행 창 닫기"><Icon as={X} size={16} /></button>
      </div>

      <div className={s.miniTrack} ref={trackRef}>
        {nodes.map((n, i) => {
          const st = byNode.get(n.id);
          const state: NodeRunState = st ? st.status : final ? 'SKIPPED' : 'idle';
          const cat = (catalog.data?.get(n.type)?.category ?? 'input') as NodeCategory;
          const look = lookOf(n.type, cat);
          const prevDone = i > 0 && byNode.get(nodes[i - 1].id)?.status === 'SUCCEEDED';
          return (
            <div key={n.id} className={s.miniStep}>
              {i > 0 && <span className={`${s.miniLink} ${prevDone && st ? (state === 'RUNNING' || state === 'WAITING' ? s.linkRun : s.linkDone) : ''}`} />}
              <div className={`${s.miniNode} ${s[`ms-${state}`]}`} style={{ ['--accent' as string]: TONE_COLOR[look.tone] }}
                   data-current={st && st.id === current?.id ? 'true' : undefined} title={catalog.data?.get(n.type)?.name ?? n.type}>
                <span className={s.miniArt}><Illustration kind={look.art} className={s.miniIll} /></span>
                <span className={s.miniName}>{catalog.data?.get(n.type)?.name ?? n.type}</span>
                <span className={s.miniState}>{state === 'WAITING' && approvalWait ? '결재 대기' : STATE_TEXT[state]}</span>
                {state === 'RUNNING' && <span className={s.miniRunLine} />}
              </div>
            </div>
          );
        })}
      </div>

      <div className={s.miniFoot}>
        <div className={s.miniProgress}><span style={{ width: `${pct}%` }} className={status === 'FAILED' ? s.progFail : ''} /></div>
        <span className="nx-num">{done}/{total} · {secs.toFixed(1)}초</span>
      </div>
      <p className={s.miniLine}>{rejected ? '결재가 반려되어 흐름을 마쳤어요.' : approvalWait ? '결재자의 승인을 기다리고 있어요. 결과가 나오면 이어서 진행해요.' : stepLine(current, curName)}</p>
      {status === 'WAITING' && !approvalWait && (
        <Button type="primary" block onClick={onExpand}>확인하기</Button>
      )}
      {final && (status !== 'SUCCEEDED' || rejected) && <Button block onClick={onExpand}>실행 화면에서 보기</Button>}
    </section>
  );
}
