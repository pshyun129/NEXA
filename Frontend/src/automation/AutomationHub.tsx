import { useEffect, useMemo, useRef, useState } from 'react';
import { Input, Modal, Skeleton } from 'antd';
import { useLocation } from 'react-router-dom';
import {
  runBadgeValue, useAvailableWorkflows, useInputForm, useMyPendingRuns, useMyRuns, useStartRun, useWorkflow, useWorkflowNames,
  type RunStatus, type ScreenCode,
} from '@/api/ai';
import { BuilderEditor } from '@/builder/BuilderEditor';
import { useViewport } from '@/shell/useViewport';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { StatusBadge } from '@/ui/StatusBadge';
import { EmptyState } from '@/ui/primitives';
import { useNotify } from '@/ui/useNotify';
import { Icon } from '@/ui/Icon';
import { ArrowLeft, ArrowUpRight, History, Play, Search, Sparkles, Workflow, X } from '@/ui/icons';
import { formatDateTime, formatElapsed, serverUtc } from '@/format';
import { RunMonitor } from './RunMonitor';
import { SchemaForm } from './SchemaForm';
import { useAutomationUi, type HubTab, type HubView } from './automationStore';
import { NewWorkflowButton, WorkflowLibrary, tintOf } from './WorkflowLibrary';
import s from './hub.module.css';

/** 지금 화면 → 실행 진입 화면 코드(요구사항 3.7, 빌더 placements와 같은 값) */
export function screenOf(pathname: string): { code: ScreenCode; label: string } {
  if (pathname.startsWith('/attendance')) return { code: 'ATTENDANCE', label: '근태' };
  if (pathname.startsWith('/leave')) return { code: 'LEAVE', label: '휴가' };
  if (pathname.startsWith('/approvals')) return { code: 'APPROVAL_DRAFT', label: '전자결재' };
  if (pathname.startsWith('/pay')) return { code: 'PAYROLL', label: '급여' };
  return { code: 'HOME', label: '홈' };
}

/** 카드 그림: 영수증 + 번개(실행) */
function CardArt() {
  return (
    <svg viewBox="0 0 120 78" fill="none" aria-hidden className={s.cardArtSvg}>
      <ellipse cx="61" cy="67" rx="37" ry="5" fill="currentColor" opacity=".09" />
      <g transform="rotate(-6 56 38)">
        <path d="M36 12h40v46l-5-3-5 3-5-3-5 3-5-3-5 3-5-3-5 3z" fill="white" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round" />
        <path d="M43 22h26M43 29h18M43 36h22" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" opacity=".4" />
      </g>
      <circle cx="81" cy="50" r="14" fill="currentColor" />
      <path d="m82 40-7 11h6l-1 9 8-12h-6l1-8" fill="white" />
    </svg>
  );
}

/** 실행하기: 지금 화면에 놓인 배포 워크플로 */
function RunTab({ screen }: { screen: { code: ScreenCode; label: string } }) {
  const wfs = useAvailableWorkflows(screen.code);
  const setView = useAutomationUi((st) => st.setView);
  const [q, setQ] = useState('');
  const list = useMemo(() => {
    const needle = q.trim();
    return (wfs.data ?? []).filter((w) => !needle || w.name.includes(needle) || (w.description ?? '').includes(needle));
  }, [wfs.data, q]);
  return (
    <div className={s.tabBody}>
      <div className={s.tabTools}>
        <Input allowClear size="large" prefix={<Icon as={Search} size={16} />} placeholder="자동화 이름으로 찾기" aria-label="자동화 검색"
               value={q} onChange={(e) => setQ(e.target.value)} style={{ maxWidth: 420 }} />
        <span className={s.muted}>{screen.label} 화면에 놓인 자동화 · 어느 화면에 놓을지는 각 워크플로의 [노출 화면]에서 정해요</span>
      </div>
      {wfs.isError ? <ErrorPanel error={wfs.error} onRetry={() => wfs.refetch()} />
        : !wfs.data ? <Skeleton active={false} paragraph={{ rows: 3 }} />
        : list.length === 0 ? (
          <EmptyState icon={Workflow} title={q ? `'${q}'에 맞는 자동화가 없어요` : '이 화면에 놓인 자동화가 아직 없어요'}
                      description="배포하고 이 화면에 노출한 워크플로가 여기에 보여요." />
        ) : (
          <div className={s.runGrid}>
            {list.map((w, i) => (
              <button key={w.id} type="button" className={`${s.runCard} ${s[`t-${tintOf(w.id)}`]}`} style={{ animationDelay: `${i * 50}ms` }}
                      onClick={() => setView({ kind: 'start', workflowId: w.id })} aria-label={`${w.name} 실행 준비`}>
                <span className={s.cardArt}><CardArt /></span>
                <strong>{w.name}</strong>
                {w.description && <span className={s.runDesc}>{w.description}</span>}
                <span className={s.runMeta}>{w.deployedVersion != null && <span>v{w.deployedVersion}</span>}<span>수정 {formatDateTime(serverUtc(w.updatedAt), true)}</span></span>
                <span className={s.runGo}><Icon as={Play} size={12} /> 실행</span>
              </button>
            ))}
          </div>
        )}
    </div>
  );
}

const HISTORY_FILTERS: { label: string; value: RunStatus | 'ALL' }[] = [
  { label: '전체', value: 'ALL' }, { label: '확인 대기', value: 'WAITING' }, { label: '실행 중', value: 'RUNNING' },
  { label: '완료', value: 'SUCCEEDED' }, { label: '실패', value: 'FAILED' }, { label: '취소', value: 'CANCELED' },
];

/** 내 실행 기록(최근 50건) / 확인 대기 */
function RunsTab({ waitingOnly }: { waitingOnly?: boolean }) {
  const [filter, setFilter] = useState<RunStatus | 'ALL'>('ALL');
  // 확인 대기 탭은 탑바 숫자와 같은 목록(내가 처리할 사람 확인만, 결재 대기는 결재함이 맡는다)
  const mine = useMyRuns(waitingOnly ? null : filter === 'ALL' ? null : filter, !waitingOnly);
  const pendingRuns = useMyPendingRuns(!!waitingOnly);
  const runs = waitingOnly ? pendingRuns : mine;
  const names = useWorkflowNames((runs.data ?? []).map((r) => r.workflowId));
  const setView = useAutomationUi((st) => st.setView);
  return (
    <div className={s.tabBody}>
      {!waitingOnly && (
        <div className={s.chips} role="tablist" aria-label="상태 필터">
          {HISTORY_FILTERS.map((f) => (
            <button key={f.value} type="button" role="tab" aria-selected={filter === f.value} className={filter === f.value ? s.chipOn : ''}
                    onClick={() => setFilter(f.value)}>{f.label}</button>
          ))}
        </div>
      )}
      {runs.isError ? <ErrorPanel error={runs.error} onRetry={() => runs.refetch()} />
        : !runs.data ? <Skeleton active={false} paragraph={{ rows: 4 }} />
        : runs.data.length === 0 ? (
          <EmptyState icon={History} title={waitingOnly ? '확인을 기다리는 실행이 없어요' : '아직 실행 기록이 없어요'} />
        ) : (
          <ul className={s.runList}>
            {runs.data.map((r, i) => (
              <li key={r.id} style={{ animationDelay: `${Math.min(i, 8) * 30}ms` }}>
                <button type="button" className={s.runRow} onClick={() => setView({ kind: 'run', runId: r.id })}>
                  <span className={s.runNo}>{r.id}</span>
                  <span className={s.runMain}>
                    <strong>{names[r.workflowId] ?? `워크플로 #${r.workflowId}`}{r.isTest && <em>테스트</em>}</strong>
                    <small className="nx-num">run #{r.id} · {formatDateTime(serverUtc(r.createdAt), true)}
                      {r.startedAt && r.finishedAt && ` · ${formatElapsed(Date.parse(serverUtc(r.finishedAt)) - Date.parse(serverUtc(r.startedAt)))}`}</small>
                  </span>
                  <StatusBadge domain="RunStatus" value={runBadgeValue(r)} />
                  <Icon as={ArrowUpRight} size={16} className={s.rowGo} />
                </button>
              </li>
            ))}
          </ul>
        )}
    </div>
  );
}

/** 실행 입력: 워크플로 설명 + 입력 폼. 시작하면 큰 창을 닫고 오른쪽 위 작은 진행 창으로 바꾼다 */
function StartView({ workflowId, screen }: { workflowId: number; screen: ScreenCode }) {
  const wf = useWorkflow(workflowId);
  const form = useInputForm(workflowId, screen);
  const start = useStartRun(workflowId);
  const minimize = useAutomationUi((st) => st.minimize);
  const notify = useNotify();
  return (
    <div className={s.startView}>
      <aside className={`${s.startInfo} ${s[`t-${tintOf(workflowId)}`]}`}>
        <span className={s.startArt}><CardArt /></span>
        <div className={s.eyebrow}>RUN AUTOMATION</div>
        <h2>{wf.data?.name ?? `워크플로 #${workflowId}`}</h2>
        <p>{wf.data?.description || '필요한 것만 채우면 나머지는 자동화가 이어서 해요.'}</p>
        <ul className={s.startSteps}>
          <li><span>01</span>입력 폼을 채우고 [실행하기]</li>
          <li><span>02</span>오른쪽 위 작은 창에서 단계별 진행을 확인</li>
          <li><span>03</span>확인이 필요하면 알림이 떠요</li>
        </ul>
      </aside>
      <section className={s.startForm}>
        {form.isError ? <ErrorPanel error={form.error} onRetry={() => form.refetch()} />
          : !form.data ? <Skeleton active={false} paragraph={{ rows: 5 }} />
          : (
            <SchemaForm
              fields={form.data.fields}
              submitting={start.isPending}
              submitLabel="실행하기"
              onSubmit={(v) => start.mutate(v, {
                onSuccess: (res) => minimize(res.runId, workflowId),
                onError: (e) => notify.error(e, '실행하지 못했습니다'),
              })}
            />
          )}
      </section>
    </div>
  );
}

const TABS: { key: HubTab; label: string }[] = [
  { key: 'run', label: '실행하기' }, { key: 'mine', label: '내 워크플로' }, { key: 'history', label: '실행 기록' }, { key: 'waiting', label: '확인 대기' },
];

/**
 * 업무 자동화 창(상단 [업무 자동화] 버튼). 화면을 거의 채우는 큰 모달 안에서
 * 실행하기 · 내 워크플로(만들기·고치기) · 실행 기록 · 확인 대기를 모두 처리한다. 빌더는 모든 사원이 쓴다.
 */
export function AutomationHub() {
  const { open, view, setView, close, openHub, origin } = useAutomationUi();
  const location = useLocation();
  const { pathname } = location;
  const { band } = useViewport();
  const screen = screenOf(pathname);
  const pending = useMyPendingRuns();
  const dirtyRef = useRef(false);

  // 창 안의 링크(결재 문서 열기 등)로 화면이 바뀌면 창을 닫는다.
  // 예전 주소(/automation/runs/12 등)로 들어와 홈으로 옮겨진 경우에는 state에 담긴 화면으로 연다
  const first = useRef(true);
  useEffect(() => {
    const want = (location.state as { openAutomation?: HubView } | null)?.openAutomation;
    if (want) { openHub(want); first.current = false; return; }
    if (first.current) { first.current = false; return; }
    close();
  }, [location.key]); // eslint-disable-line react-hooks/exhaustive-deps

  const confirmLeave = () => !dirtyRef.current || window.confirm('저장하지 않은 변경이 있습니다. 닫을까요?');
  const onClose = () => { if (confirmLeave()) { dirtyRef.current = false; close(); } };
  const toHome = (tab: HubTab) => { if (confirmLeave()) { dirtyRef.current = false; setView({ kind: 'home', tab }); } };
  const openBuilder = (id: number) => setView({ kind: 'builder', workflowId: id });

  const tab: HubTab = view.kind === 'home' ? view.tab ?? 'run' : 'run';
  const backTab: HubTab = view.kind === 'builder' ? 'mine' : view.kind === 'run' ? 'history' : 'run';
  const waitingCount = pending.data?.length ?? 0;

  return (
    <Modal open={open} onCancel={onClose} footer={null} closable={false} destroyOnHidden centered
           width="min(1680px, 96vw)" className={s.hub} keyboard={view.kind !== 'builder'} maskClosable={false}
           // 작은 진행 창에서 열면 그 자리에서 커지고 닫으면 그 자리로 줄어든다(origin 없으면 antd 기본 확대)
           transitionName={origin ? 'nx-morph' : undefined} mousePosition={origin ?? undefined}
           styles={{ body: { padding: 0 } }} aria-labelledby="nx-hub-title">
      <div className={s.hubInner}>
        <header className={s.hubHead}>
          {view.kind !== 'home' && (
            <button type="button" className={s.hubBack} onClick={() => toHome(backTab)} aria-label="업무 자동화 목록으로">
              <Icon as={ArrowLeft} size={16} />
            </button>
          )}
          <div className={s.hubTitle}>
            <div className={s.eyebrow}>AUTOMATION</div>
            <h2 id="nx-hub-title">업무 자동화{view.kind === 'builder' && <span> / 워크플로 편집</span>}{view.kind === 'run' && <span> / 실행 화면</span>}{view.kind === 'start' && <span> / 실행</span>}</h2>
          </div>
          {view.kind === 'home' && (
            <nav className={s.hubTabs} role="tablist" aria-label="업무 자동화">
              {TABS.map((t) => (
                <button key={t.key} type="button" role="tab" aria-selected={tab === t.key} className={tab === t.key ? s.hubTabOn : ''}
                        onClick={() => setView({ kind: 'home', tab: t.key })}>
                  {t.label}{t.key === 'waiting' && waitingCount > 0 && <span className={s.tabBadge}>{waitingCount}</span>}
                </button>
              ))}
            </nav>
          )}
          <span className={s.spacer} />
          {view.kind === 'home' && <NewWorkflowButton onCreated={openBuilder} />}
          <button type="button" className={s.hubClose} onClick={onClose} aria-label="업무 자동화 닫기"><Icon as={X} size={20} /></button>
        </header>

        <div className={s.hubBody}>
          {view.kind === 'home' && (
            tab === 'run' ? <RunTab screen={screen} />
              : tab === 'mine' ? <div className={s.tabBody}><WorkflowLibrary onOpen={openBuilder} /></div>
              : tab === 'history' ? <RunsTab />
              : <RunsTab waitingOnly />
          )}
          {view.kind === 'start' && <StartView key={view.workflowId} workflowId={view.workflowId} screen={screen.code} />}
          {view.kind === 'run' && <div className={s.runView}><RunMonitor key={view.runId} runId={view.runId} documentId={view.documentId} /></div>}
          {view.kind === 'builder' && (
            band !== 'wide'
              ? <EmptyState icon={Sparkles} title="1280px 이상 화면에서 열립니다" description="창을 넓히거나 브라우저 확대를 100%로 맞춰 주세요(Ctrl+0)." />
              : <BuilderEditor key={view.workflowId} id={view.workflowId} onDirtyChange={(d) => { dirtyRef.current = d; }} />
          )}
        </div>
      </div>
    </Modal>
  );
}
