import { Link, useNavigate } from 'react-router-dom';
import { Button, Skeleton, Table } from 'antd';
import { useSession } from '@/auth/session';
import { useWeekly } from '@/api/attendance';
import { useMyPendingRuns } from '@/api/ai';
import { usePendingDocuments, type DocumentSummary } from '@/api/approval';
import { docColumns } from '@/approval/columns';
import { PageHeader, Card, Counter, EmptyState } from '@/ui/primitives';
import { Icon } from '@/ui/Icon';
import { Sparkles } from '@/ui/icons';
import { openAutomation } from '@/automation/automationStore';
import { LeaveHomeCard } from '@/leave/Balances';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { ClockPanel } from '@/attendance/Clock';
import { WeeklyGauge, WeeklySummary, mondayOf } from '@/attendance/Weekly';
import { UnapprovedOtCta, YesterdayOtNotice } from '@/attendance/AnomalyChips';
import { RequestStatusCell, targetText } from '@/attendance/requests/RequestList';
import { REQUESTS_PATH, newRequestHref, requestHref } from '@/attendance/requests/links';
import { useMyRequests } from '@/api/attendanceRequests';
import { requestTypeLabel } from '@/status/labels';
import rs from '@/attendance/requests/requests.module.css';
import { formatDate, formatDurationShort, formatRelative, kst, nowKst, serverUtc } from '@/format';
import s from './home.module.css';

function TodoRow() {
  const runs = useMyPendingRuns();
  const docs = usePendingDocuments();
  const week = useWeekly(mondayOf(nowKst()));
  const anomalies = week.data?.days.reduce((n, d) => n + d.anomalies.length, 0);
  return (
    <div className={s.todos}>
      <Link to="/approvals" className={s.todo}>
        <span>결재 대기</span>
        {docs.isSuccess ? <Counter value={docs.data.length} tone="brand" label="결재 대기" /> : <span className={s.todoMuted}>—</span>}
      </Link>
      <button type="button" className={s.todo} onClick={() => openAutomation({ kind: 'home', tab: 'waiting' })}>
        <span>자동화 확인 대기</span>
        {runs.isSuccess ? <Counter value={runs.data.length} tone="warning" label="자동화 확인 대기" /> : <span className={s.todoMuted}>—</span>}
      </button>
      <Link to="/attendance/weekly" className={s.todo}>
        <span>근태이상 이번 주</span>
        {anomalies !== undefined ? <Counter value={anomalies} tone="warning" label="근태이상" /> : <span className={s.todoMuted}>—</span>}
      </Link>
    </div>
  );
}

function WeekCard() {
  const q = useWeekly(mondayOf(nowKst()));
  const today = nowKst().format('YYYY-MM-DD');
  return (
    <Card title="이번 주 근로" meta={<Link to="/attendance/weekly">주간 보기 →</Link>}>
      {q.isError ? <ErrorPanel error={q.error} onRetry={() => q.refetch()} />
        : !q.data ? <Skeleton active={false} paragraph={{ rows: 3 }} />
        : (
          <div className={s.week}>
            <WeeklySummary w={q.data} />
            <WeeklyGauge w={q.data} />
            <UnapprovedOtCta days={q.data.days} />
            <div className={s.days}>
              {q.data.days.filter((d) => !d.isHoliday || d.workTime.totalMin > 0).map((d) => (
                <span key={d.workDate} className={d.workDate === today ? s.dayToday : undefined}>
                  {kst(d.workDate).format('dd')} <b className="nx-num">{d.workTime.totalMin ? formatDurationShort(d.workTime.totalMin) : '—'}</b>
                </span>
              ))}
            </div>
          </div>
        )}
    </Card>
  );
}

/** 결재 대기(내가 결재할 차례) 상위 5건. 결재함 결재 대기 탭과 같은 쿼리(box=PENDING, 오래된 순). */
function PendingCard() {
  const q = usePendingDocuments();
  const navigate = useNavigate();
  const total = q.data?.length;
  return (
    <Card
      title={<>결재 대기{total ? <> <Counter value={total} label="결재 대기" /></> : null}</>}
      meta={<Link to="/approvals">결재함에서 처리 →</Link>}
      flush
    >
      {q.isError ? <div style={{ padding: 16 }}><ErrorPanel error={q.error} onRetry={() => q.refetch()} /></div>
        : !q.data ? <div style={{ padding: 16 }}><Skeleton active={false} paragraph={{ rows: 3 }} /></div>
        : q.data.length === 0 ? <EmptyState title="결재할 문서가 없어요" description="새 문서가 오면 여기에 표시됩니다" />
        : (
          <Table<DocumentSummary>
            rowKey="documentId"
            size="middle"
            pagination={false}
            dataSource={q.data.slice(0, 5)}
            columns={docColumns({ drafter: true, progress: true })}
            onRow={(r) => ({
              onClick: () => navigate(`/approvals/${r.documentId}`),
              onKeyDown: (e) => { if (e.key === 'Enter') navigate(`/approvals/${r.documentId}`); },
              tabIndex: 0,
              style: { cursor: 'pointer' },
            })}
          />
        )}
    </Card>
  );
}

/** 내 최근 근태 신청 5건(올해). 결재 대기 행이 있으면 목록 화면과 같은 규칙으로 5초마다 확인한다 */
function RecentRequestsCard() {
  const q = useMyRequests(nowKst().year());
  return (
    <Card title="내 최근 신청" meta={<Link to={REQUESTS_PATH}>전체 보기 →</Link>} flush>
      {q.isError ? <div style={{ padding: 16 }}><ErrorPanel error={q.error} onRetry={() => q.refetch()} /></div>
        : !q.data ? <div style={{ padding: 16 }}><Skeleton active={false} paragraph={{ rows: 3 }} /></div>
        : q.data.length === 0 ? (
          <EmptyState title="올해 근태 신청이 없습니다"
                      action={<Link to={newRequestHref({ type: 'TIME_FIX' })}><Button size="small">새 신청</Button></Link>} />
        ) : (
          <ul className={rs.recent}>
            {q.data.slice(0, 5).map((r) => (
              <li key={r.attReqId}>
                <Link to={requestHref(r.attReqId)}>
                  <span>{requestTypeLabel(r.reqType)}</span>
                  <span className={`${s.todoMuted} nx-num`}>{targetText(r)}</span>
                  <span style={{ marginLeft: 'auto' }}><RequestStatusCell r={r} /></span>
                </Link>
              </li>
            ))}
          </ul>
        )}
    </Card>
  );
}

/** 대기 건이 있을 때만 보인다(설계 6.1). 사유(reasons)는 목록 응답에 없어 표시하지 않는다(frontend-backend-gaps.md B5). */
function AiWaitingCard() {
  const q = useMyPendingRuns();
  if (!q.isSuccess || q.data.length === 0) return null;
  return (
    <div className={s.aiCard}>
      <div className={s.aiHead}><Icon as={Sparkles} className={s.aiIcon} /> <b>AI가 확인을 기다립니다 ({q.data.length})</b></div>
      <ul className={s.aiList}>
        {q.data.slice(0, 3).map((r) => (
          <li key={r.id}>
            <span className="nx-mono">run #{r.id}</span>
            <span className={s.todoMuted}>워크플로 #{r.workflowId} · {formatRelative(serverUtc(r.createdAt))}</span>
            <Button size="small" onClick={() => openAutomation({ kind: 'run', runId: r.id })}>확인하기 →</Button>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** 홈(설계 6.1): 처리할 일이 맨 위. */
export function HomePage() {
  const { me } = useSession();
  return (
    <>
      <PageHeader title={`${me?.name ?? ''}님, 안녕하세요`} description={formatDate(nowKst())} />
      <div className={s.stack}>
        <Card title="처리할 일"><TodoRow /></Card>
        <div className={s.grid3}>
          <Card title="오늘 근무"><ClockPanel /><YesterdayOtNotice /></Card>
          <WeekCard />
          <Card title="휴가 잔여"><LeaveHomeCard /></Card>
        </div>
        <div className={s.grid2}>
          <PendingCard />
          <RecentRequestsCard />
        </div>
        <AiWaitingCard />
      </div>
    </>
  );
}
