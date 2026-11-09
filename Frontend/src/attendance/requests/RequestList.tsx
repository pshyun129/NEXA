import { Button, Select, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useMyRequests, type AttendanceRequestType, type RequestSummary } from '@/api/attendanceRequests';
import { StatusBadge } from '@/ui/StatusBadge';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { EmptyState } from '@/ui/primitives';
import { formatDateShort, formatDateTime, formatTime, nowKst } from '@/format';
import { requestTypeLabel } from '@/status/labels';
import { newRequestHref, requestHref } from './links';
import s from './requests.module.css';

const TYPES: AttendanceRequestType[] = ['TIME_FIX', 'OVERTIME', 'HOLIDAY_SUBST'];

/** 대상일. 휴일대체는 "근무한 휴일 → 대신 쉴 날" */
export function targetText(r: Pick<RequestSummary, 'reqType' | 'targetDate' | 'substDate'>) {
  return r.reqType === 'HOLIDAY_SUBST' && r.substDate
    ? `${formatDateShort(r.targetDate)} → ${formatDateShort(r.substDate)}`
    : formatDateShort(r.targetDate);
}

/** 상태 + 결재 결과 반영 시각(설계 7.3: 완료 행은 "근태 반영 HH:mm:ss") */
export function RequestStatusCell({ r }: { r: Pick<RequestSummary, 'status' | 'completedAt'> }) {
  return (
    <span className={s.statusCell}>
      <StatusBadge domain="AttendanceRequestStatus" value={r.status} />
      {r.completedAt && r.status === 'APPROVED' && (
        <span className={`${s.applied} nx-num`}>근태 반영 {formatTime(r.completedAt, true)}</span>
      )}
    </span>
  );
}

/** 내 근태 신청 목록(요구사항 3.3). year는 필수라 올해가 기본값 */
export function RequestList() {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const thisYear = nowKst().year();
  const year = Number(params.get('year')) || thisYear;
  const typeParam = params.get('type');
  const type = (TYPES as string[]).includes(typeParam ?? '') ? (typeParam as AttendanceRequestType) : undefined;
  const q = useMyRequests(year, type);

  const setParam = (k: string, v: string | undefined) => {
    const next = new URLSearchParams(params);
    if (v) next.set(k, v); else next.delete(k);
    setParams(next, { replace: true });
  };

  const cols: ColumnsType<RequestSummary> = [
    { title: '유형', dataIndex: 'reqType', width: 120, render: (v: string) => requestTypeLabel(v) },
    { title: '대상일', key: 'target', width: 170, render: (_, r) => <span className="nx-num">{targetText(r)}</span> },
    { title: '사유', dataIndex: 'reason', render: (v: string) => <span className={s.reason} title={v}>{v}</span> },
    { title: '상태', key: 'status', width: 150, render: (_, r) => <RequestStatusCell r={r} /> },
    { title: '상신', dataIndex: 'createdAt', width: 120, render: (v: string) => <span className="nx-num">{formatDateTime(v, true)}</span> },
  ];

  const open = (id: number) => navigate(`${requestHref(id)}${params.toString() ? `?${params.toString()}` : ''}`);

  return (
    <div>
      <div className={s.toolbar}>
        <Select style={{ width: 100 }} value={year} aria-label="연도"
                options={[thisYear, thisYear - 1, thisYear - 2].map((y) => ({ value: y, label: `${y}년` }))}
                onChange={(v) => setParam('year', v === thisYear ? undefined : String(v))} />
        <Select allowClear placeholder="유형" style={{ width: 140 }} value={type} aria-label="유형 필터"
                options={TYPES.map((t) => ({ value: t, label: requestTypeLabel(t) }))}
                onChange={(v) => setParam('type', v)} />
        {q.poll.active && <span className={s.muted} role="status">결재 결과를 5초마다 확인하는 중</span>}
        {q.poll.expired && (
          <span className={s.muted}>
            자동 확인을 멈췄습니다 <Button size="small" type="link" onClick={() => q.refetch()}>다시 확인</Button>
          </span>
        )}
        <span className={s.spacer} />
        <Link to={newRequestHref({ type: type ?? 'TIME_FIX' })}><Button type="primary">새 신청</Button></Link>
      </div>
      {q.isError ? <ErrorPanel error={q.error} onRetry={() => q.refetch()} /> : (
        <Table<RequestSummary>
          rowKey="attReqId"
          size="middle"
          loading={q.isPending}
          columns={cols}
          dataSource={q.data ?? []}
          pagination={(q.data?.length ?? 0) > 20 ? { pageSize: 20, showSizeChanger: false, size: 'small' } : false}
          locale={{
            emptyText: (
              <EmptyState
                title={`${year}년 근태 신청이 없습니다`}
                description="근무시간 수정, 초과근무, 휴일대체를 신청할 수 있습니다"
                action={<Link to={newRequestHref({ type: 'TIME_FIX' })}><Button>새 신청</Button></Link>}
              />
            ),
          }}
          onRow={(r) => ({
            onClick: () => open(r.attReqId),
            onKeyDown: (e) => { if (e.key === 'Enter') open(r.attReqId); },
            tabIndex: 0,
            style: { cursor: 'pointer' },
          })}
        />
      )}
    </div>
  );
}
