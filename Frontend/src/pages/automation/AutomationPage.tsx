import { Button, Skeleton } from 'antd';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import {
  useAvailableWorkflows, useInputForm, useMyPendingRuns, useStartRun, useWorkflow,
  type ScreenCode,
} from '@/api/ai';
import { Card, EmptyState, PageHeader } from '@/ui/primitives';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { StatusBadge } from '@/ui/StatusBadge';
import { useNotify } from '@/ui/useNotify';
import { Icon } from '@/ui/Icon';
import { Workflow } from '@/ui/icons';
import { formatRelative, serverUtc } from '@/format';
import { SchemaForm } from '@/automation/SchemaForm';
import { RunMonitor } from '@/automation/RunMonitor';
import { usePreviewStore } from '@/automation/previewStore';
import s from '@/automation/automation.module.css';

/** 사원 업무 자동화 메뉴의 진입 화면 코드(요구사항 3.7: 시연은 HOME만 쓴다) */
const SCREEN: ScreenCode = 'HOME';

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
    </div>
  );
}

function StartRun({ id }: { id: number }) {
  const wf = useWorkflow(id);
  const form = useInputForm(id, SCREEN);
  const start = useStartRun(id);
  const navigate = useNavigate();
  const notify = useNotify();
  const attach = usePreviewStore((st) => st.attachToRun);
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
          onSuccess: (res) => {
            attach(res.runId, v.fileIds);
            navigate(`/automation/runs/${res.runId}`);
          },
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
