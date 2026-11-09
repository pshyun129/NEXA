import { useState } from 'react';
import { Button, Segmented, Skeleton } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import {
  useAvailableWorkflows, useInputForm, useMyPendingRuns, useMyRuns, useStartRun, useWorkflow, useWorkflowNames,
  type RunStatus, type RunSummary, type ScreenCode,
} from '@/api/ai';
import { DataTable } from '@/ui/DataTable';
import { Card, EmptyState, PageHeader } from '@/ui/primitives';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { StatusBadge } from '@/ui/StatusBadge';
import { useNotify } from '@/ui/useNotify';
import { Icon } from '@/ui/Icon';
import { Workflow } from '@/ui/icons';
import { formatDateTime, formatElapsed, formatRelative, serverUtc } from '@/format';
import { SchemaForm } from '@/automation/SchemaForm';
import { RunMonitor } from '@/automation/RunMonitor';
import s from '@/automation/automation.module.css';

/** 사원 업무 자동화 메뉴의 진입 화면 코드(요구사항 3.7: 시연은 HOME만 쓴다) */
const SCREEN: ScreenCode = 'HOME';

const HISTORY_FILTERS: { label: string; value: RunStatus | 'ALL' }[] = [
  { label: '전체', value: 'ALL' },
  { label: '확인 대기', value: 'WAITING' },
  { label: '실행 중', value: 'RUNNING' },
  { label: '완료', value: 'SUCCEEDED' },
  { label: '실패', value: 'FAILED' },
  { label: '취소', value: 'CANCELED' },
];

/** 내 실행 이력(P1). 서버가 최근 50건만 준다. 행을 누르면 실행 화면 */
function History() {
  const [filter, setFilter] = useState<RunStatus | 'ALL'>('ALL');
  const runs = useMyRuns(filter === 'ALL' ? null : filter);
  const names = useWorkflowNames((runs.data ?? []).map((r) => r.workflowId));
  const navigate = useNavigate();
  const columns: ColumnsType<RunSummary> = [
    { title: '실행', dataIndex: 'id', width: 90, render: (id: number) => <span className="nx-mono">run #{id}</span> },
    {
      title: '자동화', dataIndex: 'workflowId',
      render: (id: number, r) => <>{names[id] ?? `워크플로 #${id}`}{r.isTest && <span className={s.tag} style={{ marginLeft: 8 }}>테스트</span>}</>,
    },
    { title: '상태', dataIndex: 'status', width: 120, render: (v: RunStatus) => <StatusBadge domain="RunStatus" value={v} /> },
    { title: '시작', dataIndex: 'createdAt', width: 130, render: (v: string) => <span className="nx-num">{formatDateTime(serverUtc(v), true)}</span> },
    {
      title: '소요', key: 'elapsed', width: 90, align: 'right',
      render: (_, r) => <span className="nx-num">{r.startedAt && r.finishedAt
        ? formatElapsed(Date.parse(serverUtc(r.finishedAt)) - Date.parse(serverUtc(r.startedAt))) : '—'}</span>,
    },
  ];
  return (
    <Card title="내 실행 이력" meta={<span className={s.muted}>최근 50건</span>}>
      <Segmented<RunStatus | 'ALL'> options={HISTORY_FILTERS} value={filter} onChange={setFilter} style={{ marginBottom: 12 }} />
      {runs.isError ? <ErrorPanel error={runs.error} onRetry={() => runs.refetch()} /> : (
        <DataTable<RunSummary>
          data={runs.data}
          loading={runs.isPending}
          rowKey="id"
          columns={columns}
          emptyTitle={filter === 'ALL' ? '아직 실행한 자동화가 없습니다' : '이 상태의 실행이 없습니다'}
          onRow={(r) => ({ onClick: () => navigate(`/automation/runs/${r.id}`), style: { cursor: 'pointer' } })}
        />
      )}
    </Card>
  );
}

function Entry() {
  const wfs = useAvailableWorkflows(SCREEN);
  const pending = useMyPendingRuns();
  return (
    <div className={s.stack}>
      <Card title="실행할 수 있는 자동화">
        {wfs.isError ? <ErrorPanel error={wfs.error} onRetry={() => wfs.refetch()} />
          : !wfs.data ? <Skeleton active={false} paragraph={{ rows: 3 }} />
          : wfs.data.length === 0 ? <EmptyState icon={Workflow} title="실행할 수 있는 자동화가 없습니다" description="관리자가 배포한 자동화가 여기에 표시됩니다" />
          : (
            <ul className={s.wfList}>
              {wfs.data.map((w) => (
                <li key={w.id}>
                  <Link to={`/automation/workflows/${w.id}`} className={s.wfItem}>
                    <Icon as={Workflow} />
                    <span className={s.wfText}><b>{w.name}</b>{w.description && <span className={s.muted}>{w.description}</span>}</span>
                    <span className={s.muted}>실행 →</span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
      </Card>
      {pending.isSuccess && pending.data.length > 0 && (
        <Card title={`확인 대기 (${pending.data.length})`}>
          <ul className={s.wfList}>
            {pending.data.map((r) => (
              <li key={r.id}>
                <Link to={`/automation/runs/${r.id}`} className={s.wfItem}>
                  <span className="nx-mono">run #{r.id}</span>
                  <span className={s.muted}>워크플로 #{r.workflowId} · {formatRelative(serverUtc(r.createdAt))}</span>
                  {r.isTest && <span className={s.tag}>테스트</span>}
                  <span className={s.spacer} />
                  <StatusBadge domain="RunStatus" value={r.status} />
                </Link>
              </li>
            ))}
          </ul>
        </Card>
      )}
      <History />
    </div>
  );
}

function StartRun({ id }: { id: number }) {
  const wf = useWorkflow(id);
  const form = useInputForm(id, SCREEN);
  const start = useStartRun(id);
  const navigate = useNavigate();
  const notify = useNotify();
  if (form.isError) return <ErrorPanel error={form.error} onRetry={() => form.refetch()} />;
  if (!form.data) return <Skeleton active={false} paragraph={{ rows: 5 }} />;
  return (
    <Card title={wf.data?.name ?? `워크플로 #${id}`} meta={<Link to="/automation">← 자동화 목록</Link>}>
      {wf.data?.description && <p className={s.muted} style={{ marginTop: 0 }}>{wf.data.description}</p>}
      <SchemaForm
        fields={form.data.fields}
        submitting={start.isPending}
        submitLabel="실행"
        onSubmit={(v) => start.mutate(v, {
          onSuccess: (res) => navigate(`/automation/runs/${res.runId}`),
          onError: (e) => notify.error(e, '실행하지 못했습니다'),
        })}
      />
    </Card>
  );
}

/** 업무 자동화(요구사항 3.7, 설계 6.5): 진입 목록 → 입력 폼 → 실행 화면 */
export function AutomationPage() {
  const { pathname } = useLocation();
  const wf = pathname.match(/^\/automation\/workflows\/(\d+)/);
  const run = pathname.match(/^\/automation\/runs\/(\d+)/);
  return (
    <>
      <PageHeader title="업무 자동화" actions={run || wf ? <Link to="/automation"><Button>자동화 목록</Button></Link> : undefined} />
      {run ? <RunMonitor key={run[1]} runId={Number(run[1])} />
        : wf ? <StartRun key={wf[1]} id={Number(wf[1])} />
        : <Entry />}
    </>
  );
}
