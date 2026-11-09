import { useEffect, useMemo, useState } from 'react';
import { Input, Modal, Skeleton } from 'antd';
import { useLocation, useNavigate } from 'react-router-dom';
import {
  useAvailableWorkflows, useInputForm, useMyRuns, useStartRun, useWorkflowNames,
  type ScreenCode, type WorkflowSummary,
} from '@/api/ai';
import { SchemaForm } from '@/automation/SchemaForm';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { StatusBadge } from '@/ui/StatusBadge';
import { Icon } from '@/ui/Icon';
import { ArrowLeft, ArrowUpRight, Search, Workflow } from '@/ui/icons';
import { Kbd, modKey } from '@/ui/primitives';
import { useNotify } from '@/ui/useNotify';
import { formatDateTime, serverUtc } from '@/format';
import s from './launcher.module.css';

/** 지금 화면 → 실행 진입 화면 코드(요구사항 3.7, 빌더 placements와 같은 값) */
function screenOf(pathname: string): { code: ScreenCode; label: string } {
  if (pathname.startsWith('/attendance')) return { code: 'ATTENDANCE', label: '근태' };
  if (pathname.startsWith('/leave')) return { code: 'LEAVE', label: '휴가' };
  if (pathname.startsWith('/approvals')) return { code: 'APPROVAL_DRAFT', label: '전자결재' };
  if (pathname.startsWith('/pay')) return { code: 'PAYROLL', label: '급여' };
  return { code: 'HOME', label: '홈' };
}

/** 카드 색: 워크플로마다 파스텔 하나(노드 카테고리 색을 돌려 쓴다) */
const TINTS = ['output', 'input', 'rule', 'ai', 'human'] as const;
const tintOf = (id: number) => TINTS[id % TINTS.length];

/** 카드 그림: 문서 + 번개(실행) */
function CardArt() {
  return (
    <svg viewBox="0 0 120 78" fill="none" aria-hidden className={s.art}>
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

function RunForm({ wf, screen, onDone, onBack }:
  { wf: WorkflowSummary; screen: ScreenCode; onDone: (runId: number) => void; onBack: () => void }) {
  const form = useInputForm(wf.id, screen);
  const start = useStartRun(wf.id);
  const notify = useNotify();
  return (
    <div className={s.formStep}>
      <button type="button" className={s.back} onClick={onBack}><Icon as={ArrowLeft} size={16} />목록으로</button>
      <div className={`${s.pick} ${s[`t-${tintOf(wf.id)}`]}`}>
        <span className={s.pickArt}><CardArt /></span>
        <div><strong>{wf.name}</strong>{wf.description && <small>{wf.description}</small>}</div>
      </div>
      {form.isError ? <ErrorPanel error={form.error} onRetry={() => form.refetch()} />
        : !form.data ? <Skeleton active={false} paragraph={{ rows: 4 }} />
        : (
          <SchemaForm
            fields={form.data.fields}
            submitting={start.isPending}
            submitLabel="실행하기"
            onSubmit={(v) => start.mutate(v, {
              onSuccess: (res) => onDone(res.runId),
              onError: (e) => notify.error(e, '실행하지 못했습니다'),
            })}
          />
        )}
    </div>
  );
}

/** 창 안쪽. 열렸을 때만 그려서(destroyOnHidden) 목록·최근 실행은 그때만 불러온다 */
function LauncherBody({ screen, onClose }: { screen: { code: ScreenCode; label: string }; onClose: (to?: string) => void }) {
  const [tab, setTab] = useState<'here' | 'recent'>('here');
  const [q, setQ] = useState('');
  const [pick, setPick] = useState<WorkflowSummary | null>(null);
  const wfs = useAvailableWorkflows(screen.code);
  const runs = useMyRuns(null);
  const recent = (runs.data ?? []).slice(0, 6);
  const names = useWorkflowNames(recent.map((r) => r.workflowId));

  const list = useMemo(() => {
    const needle = q.trim();
    return (wfs.data ?? []).filter((w) => !needle || w.name.includes(needle) || (w.description ?? '').includes(needle));
  }, [wfs.data, q]);

  return (
    <>
      <div className={s.head}>
        <div className={s.eyebrow}>{pick ? 'RUN AUTOMATION' : 'AUTOMATION LAUNCHER'}</div>
        <div className={s.title} id="nx-launcher-title">{pick ? pick.name : '어떤 일을 맡길까요?'}</div>
        <div className={s.sub}>
          {pick ? '필요한 것만 채우면 나머지는 자동화가 이어서 해요.'
            : `${screen.label} 화면에 놓인 자동화${wfs.data ? ` ${wfs.data.length}개` : ''}`}
        </div>
      </div>
      {pick ? (
        <RunForm wf={pick} screen={screen.code} onBack={() => setPick(null)}
                 onDone={(runId) => onClose(`/automation/runs/${runId}`)} />
      ) : (
        <>
          <Input size="large" allowClear prefix={<Icon as={Search} size={16} />} placeholder="자동화 이름으로 찾기"
                 aria-label="자동화 검색" value={q} onChange={(e) => { setQ(e.target.value); setTab('here'); }} />
          <div className={s.tabs} role="tablist" aria-label="자동화 보기">
            <button type="button" role="tab" aria-selected={tab === 'here'} className={tab === 'here' ? s.on : ''} onClick={() => setTab('here')}>
              {screen.label}에서<span>{wfs.data?.length ?? '–'}</span>
            </button>
            <button type="button" role="tab" aria-selected={tab === 'recent'} className={tab === 'recent' ? s.on : ''} onClick={() => setTab('recent')}>
              최근 실행<span>{runs.data ? recent.length : '–'}</span>
            </button>
          </div>
          {tab === 'here' ? (
            wfs.isError ? <ErrorPanel error={wfs.error} onRetry={() => wfs.refetch()} />
              : !wfs.data ? <Skeleton active={false} paragraph={{ rows: 3 }} />
              : list.length === 0 ? (
                <div className={s.empty}>
                  <strong>{q ? `'${q}'에 맞는 자동화가 없어요.` : '이 화면에 놓인 자동화가 아직 없어요.'}</strong>
                  <span>관리자가 배포하고 이 화면에 노출한 자동화가 여기에 보여요.</span>
                </div>
              ) : (
                <div className={s.grid}>
                  {list.map((w, i) => (
                    <button key={w.id} type="button" className={`${s.card} ${s[`t-${tintOf(w.id)}`]}`}
                            style={{ animationDelay: `${i * 50}ms` }} onClick={() => setPick(w)} aria-label={`${w.name} 실행 준비`}>
                      <span className={s.cardArt}><CardArt /></span>
                      <strong>{w.name}</strong>
                      {w.description && <span className={s.desc}>{w.description}</span>}
                      <span className={s.meta}>{w.deployedVersion != null && <span>v{w.deployedVersion}</span>}<span>수정 {formatDateTime(serverUtc(w.updatedAt), true)}</span></span>
                      <span className={s.go}><Icon as={ArrowUpRight} size={16} /></span>
                    </button>
                  ))}
                </div>
              )
          ) : runs.isError ? <ErrorPanel error={runs.error} onRetry={() => runs.refetch()} />
            : !runs.data ? <Skeleton active={false} paragraph={{ rows: 3 }} />
            : recent.length === 0 ? <div className={s.empty}><strong>아직 실행한 자동화가 없어요.</strong></div>
            : (
              <ul className={s.runs}>
                {recent.map((r) => (
                  <li key={r.id}>
                    <button type="button" className={s.run} onClick={() => onClose(`/automation/runs/${r.id}`)}>
                      <span className={s.runNo}>{r.id}</span>
                      <span className={s.runMain}>
                        <strong>{names[r.workflowId] ?? `워크플로 #${r.workflowId}`}</strong>
                        <small>run #{r.id} · {formatDateTime(serverUtc(r.createdAt), true)}{r.isTest ? ' · 테스트' : ''}</small>
                      </span>
                      <StatusBadge domain="RunStatus" value={r.status} />
                    </button>
                  </li>
                ))}
              </ul>
            )}
          <div className={s.foot}>
            <span>어느 화면에 놓을지는 자동화 빌더에서 정해요</span>
            <button type="button" className={s.link} onClick={() => onClose('/automation')}>
              업무 자동화 전체 보기 <Icon as={ArrowUpRight} size={12} />
            </button>
          </div>
        </>
      )}
    </>
  );
}

/**
 * 어느 화면에서든 꺼내 쓰는 자동화 실행 창. 지금 화면에 배치된(placements) 배포 워크플로를 보여 주고,
 * 입력 폼을 채워 실행하면 실행 화면(/automation/runs/:id)으로 간다. Ctrl(⌘)+J로도 연다.
 */
export function AutomationLauncher() {
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const screen = screenOf(pathname);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && !e.shiftKey && e.key.toLowerCase() === 'j') { e.preventDefault(); setOpen((v) => !v); }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const onClose = (to?: string) => { setOpen(false); if (to) navigate(to); };

  return (
    <>
      <button type="button" className={s.trigger} onClick={() => setOpen(true)} aria-haspopup="dialog">
        <Icon as={Workflow} size={16} />
        <span className={s.triggerLabel}>자동화 실행</span>
        <Kbd>{modKey}+J</Kbd>
      </button>
      <Modal open={open} onCancel={() => onClose()} footer={null} width={640} destroyOnHidden className={s.modal}
             aria-labelledby="nx-launcher-title">
        <LauncherBody screen={screen} onClose={onClose} />
      </Modal>
    </>
  );
}
