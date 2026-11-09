import { useCallback, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Button, Skeleton, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { Link } from 'react-router-dom';
import {
  leaveDay, leaveKeys, useLeaveRequest, useLeaveRequestDetails, useMyLeaveRequests,
  type LeaveRequestDetail, type LeaveRequestSummary,
} from '@/api/leave';
import { useDocument, useDocumentAction } from '@/api/approval';
import { useMyId } from '@/auth/session';
import { documentPermissions } from '@/approval/permissions';
import { StatusBadge } from '@/ui/StatusBadge';
import { AsyncSync } from '@/ui/AsyncSync';
import { ConfirmAction } from '@/ui/ConfirmAction';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { EmptyState } from '@/ui/primitives';
import { useNotify } from '@/ui/useNotify';
import { formatDate, formatDateShort, formatDateTime, formatLeaveDays, serverUtc } from '@/format';
import { leavePortionLabel } from '@/status/labels';
import { APPLY_PATH } from './links';
import s from './leave.module.css';

const PAGE = 10;

/** 날짜 요약: 11.16(월) 외 2일 */
function datesText(d: LeaveRequestDetail | undefined) {
  if (!d) return null;
  const days = d.dates.map((x) => leaveDay(x.leaveDate)).sort();
  if (days.length === 0) return '—';
  return days.length === 1 ? formatDateShort(days[0]) : `${formatDateShort(days[0])} 외 ${days.length - 1}일`;
}

/** 내 휴가 신청 목록. 목록에 날짜가 없어 보이는 페이지의 상세만 읽는다(설계 9장 #11) */
export function LeaveRequestList({ onOpen }: { onOpen: (id: number) => void }) {
  const q = useMyLeaveRequests();
  const [page, setPage] = useState(1);
  const rows = q.data ?? [];
  const visible = rows.slice((page - 1) * PAGE, page * PAGE);
  const details = useLeaveRequestDetails(visible.map((r) => r.requestId));
  const detailOf = new Map(details.map((d, i) => [visible[i]?.requestId, d.data]));

  const cols: ColumnsType<LeaveRequestSummary> = [
    { title: '유형', dataIndex: 'leaveTypeName', width: 110 },
    { title: '날짜', key: 'dates', render: (_, r) => <span className={`${s.dates} nx-num`}>{datesText(detailOf.get(r.requestId)) ?? '…'}</span> },
    { title: '차감', dataIndex: 'totalDeductMinutes', width: 90, align: 'right',
      render: (v: number) => <span className="nx-num">{formatLeaveDays(v)}</span> },
    { title: '상태', dataIndex: 'status', width: 130, render: (v: string) => <StatusBadge domain="LeaveRequestStatus" value={v} /> },
    { title: '신청', key: 'at', width: 130,
      render: (_, r) => <span className="nx-num">{formatDateTime(serverUtc(r.submittedAt ?? r.createdAt), true)}</span> },
  ];

  if (q.isError) return <ErrorPanel error={q.error} onRetry={() => q.refetch()} />;
  return (
    <Table<LeaveRequestSummary>
      rowKey="requestId"
      size="middle"
      loading={q.isPending}
      columns={cols}
      dataSource={rows}
      pagination={rows.length > PAGE ? { current: page, pageSize: PAGE, onChange: setPage, showSizeChanger: false, size: 'small' } : false}
      locale={{
        emptyText: (
          <EmptyState title="휴가 신청이 없습니다" description="달력에서 날짜를 골라 신청할 수 있습니다"
                      action={<Link to={APPLY_PATH}><Button>휴가 신청</Button></Link>} />
        ),
      }}
      onRow={(r) => ({
        onClick: () => onOpen(r.requestId),
        onKeyDown: (e) => { if (e.key === 'Enter') onOpen(r.requestId); },
        tabIndex: 0,
        style: { cursor: 'pointer' },
      })}
    />
  );
}

/**
 * 회수: 휴가 신청에는 회수 API가 없고, 결재 문서를 회수하면 Kafka로 신청이 WITHDRAWN이 된다.
 * 결재 문서가 끝났는데 신청이 아직 결재 중이면 반영을 기다린다(AsyncSync, 설계 7.3).
 */
function ApprovalPart({ req, onSynced, checkedAt }: { req: LeaveRequestDetail; onSynced: () => void; checkedAt: number }) {
  const meId = useMyId();
  const notify = useNotify();
  const doc = useDocument(req.approvalDocId);
  const action = useDocumentAction(req.approvalDocId ?? 0);
  if (req.approvalDocId == null) return null;
  const d = doc.data;
  const p = d ? documentPermissions(d, meId) : null;
  const waiting = !!d && d.status !== 'INPROGRESS' && (req.status === 'SUBMITTED' || req.status === 'SUBMITTING');
  const href = `/approvals/${req.approvalDocId}?tab=drafted`;
  return (
    <div className={s.foot}>
      {d && <span className="nx-mono">{d.docNo}</span>}
      {d && <StatusBadge domain="DocumentStatus" value={d.status} />}
      <AsyncSync waiting={waiting} onCheck={onSynced} checkedAt={checkedAt} delayedHref={href} />
      <span className={s.spacer} />
      <Link to={href}><Button>결재 문서 보기</Button></Link>
      {p?.canCancel && req.status === 'SUBMITTED' && (
        <ConfirmAction
          title="휴가 신청을 회수합니다"
          confirmLabel="회수"
          danger
          onConfirm={() => action.mutateAsync({ kind: 'cancel' }).then(
            () => { notify.success('회수했습니다', '휴가 잔여에 반영되기까지 몇 초 걸릴 수 있습니다'); onSynced(); },
            (e) => notify.error(e, '회수하지 못했습니다'),
          )}
          trigger={(open) => <Button onClick={open} disabled={action.isPending}>회수</Button>}
        >
          아직 아무도 결재하지 않았습니다. 회수하면 결재 문서가 내려가고 예약된 {formatLeaveDays(req.totalDeductMinutes)}이 돌아옵니다.
        </ConfirmAction>
      )}
    </div>
  );
}

/** 신청 상세(Drawer 본문) */
export function LeaveRequestView({ id }: { id: number }) {
  const q = useLeaveRequest(id);
  const qc = useQueryClient();
  // 반영되면 잔여·목록·원장도 바뀌므로 휴가 쿼리를 모두 다시 읽는다
  const sync = useCallback(() => { qc.invalidateQueries({ queryKey: leaveKeys.all }); }, [qc]);
  if (q.isPending) return <Skeleton active={false} paragraph={{ rows: 6 }} />;
  if (q.isError) return <ErrorPanel error={q.error} onRetry={() => q.refetch()} />;
  const r = q.data;
  const dates = [...r.dates].sort((a, b) => a.leaveDate.localeCompare(b.leaveDate));
  return (
    <div className={s.detail}>
      <div className={s.head}>
        <strong>{r.leaveTypeName}</strong>
        <StatusBadge domain="LeaveRequestStatus" value={r.status} />
        <span className={`${s.muted} nx-num`}>#{r.requestId}</span>
      </div>
      <dl className={s.kv}>
        <dt>차감</dt><dd className="nx-num">{formatLeaveDays(r.totalDeductMinutes)}</dd>
        <dt>신청</dt><dd className="nx-num">{formatDateTime(serverUtc(r.submittedAt ?? r.createdAt))}</dd>
        <dt>사유</dt><dd>{r.reason || <span className={s.muted}>없음</span>}</dd>
        {r.cancelledAt && <><dt>취소</dt><dd className="nx-num">{formatDateTime(serverUtc(r.cancelledAt))} {r.cancelReason}</dd></>}
      </dl>
      <table className={s.items}>
        <thead><tr><th>날짜</th><th>단위</th><th style={{ textAlign: 'right' }}>차감</th></tr></thead>
        <tbody>
          {dates.map((d) => (
            <tr key={d.requestDateId}>
              <td className="nx-num">{formatDate(leaveDay(d.leaveDate))}</td>
              <td>{leavePortionLabel(d.portion)}</td>
              <td className="nx-num" style={{ textAlign: 'right' }}>{formatLeaveDays(d.deductMinutes)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <ApprovalPart req={r} onSynced={sync} checkedAt={q.dataUpdatedAt} />
    </div>
  );
}
