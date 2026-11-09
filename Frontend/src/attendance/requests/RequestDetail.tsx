import { useEffect, useRef } from 'react';
import { Button, Skeleton } from 'antd';
import { Link } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { attnKeys } from '@/api/attendance';
import { useCancelRequest, useRequest, type RequestDetail as Detail } from '@/api/attendanceRequests';
import { useDocument } from '@/api/approval';
import { useMyId } from '@/auth/session';
import { documentPermissions } from '@/approval/permissions';
import { ApprovalLine } from '@/approval/ApprovalLine';
import { AsyncSync } from '@/ui/AsyncSync';
import { ConfirmAction } from '@/ui/ConfirmAction';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { StatusBadge } from '@/ui/StatusBadge';
import { useNotify } from '@/ui/useNotify';
import { formatDate, formatDateTime, formatTime } from '@/format';
import { itemActionLabel, requestTypeLabel } from '@/status/labels';
import { RequestStatusCell, targetText } from './RequestList';
import { newRequestHref } from './links';
import s from './requests.module.css';

/** 반려·회수된 신청을 같은 유형·날짜로 다시 신청(초과근무는 첫 구간을 채운다) */
function retryHref(r: Detail) {
  const first = r.items[0];
  return newRequestHref({
    type: r.reqType,
    date: r.targetDate,
    subst: r.substDate ?? undefined,
    start: r.reqType === 'OVERTIME' ? first?.startAt ?? undefined : undefined,
    end: r.reqType === 'OVERTIME' ? first?.endAt ?? undefined : undefined,
  });
}

function Items({ r }: { r: Detail }) {
  if (r.reqType === 'HOLIDAY_SUBST' || r.items.length === 0) return null;
  const overtime = r.reqType === 'OVERTIME';
  return (
    <table className={s.items}>
      <thead>
        <tr>{!overtime && <th>구분</th>}<th>상태</th><th>{overtime ? '시작' : '시각'}</th>{overtime && <th>종료</th>}</tr>
      </thead>
      <tbody>
        {r.items.map((i) => (
          <tr key={i.itemId}>
            {!overtime && <td>{itemActionLabel(i.action)}</td>}
            <td>{i.statusName}</td>
            <td className="nx-num">{formatDateTime(i.startAt, true)}</td>
            {overtime && <td className="nx-num">{formatTime(i.endAt)}</td>}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** 근태 신청 상세(요구사항 3.3, 설계 7.3) */
export function RequestDetail({ id }: { id: number }) {
  const qc = useQueryClient();
  const notify = useNotify();
  const meId = useMyId();
  const q = useRequest(id);
  const r = q.data;
  const doc = useDocument(r?.approvalDocId ?? null);
  const cancel = useCancelRequest();

  // 결재는 끝났는데(문서가 진행중이 아님) 신청은 아직 결재 대기 → Kafka 반영을 기다린다
  const waiting = r?.status === 'PENDING' && !!doc.data && doc.data.status !== 'INPROGRESS';
  const wasWaiting = useRef(false);
  useEffect(() => {
    if (wasWaiting.current && !waiting && r && r.status !== 'PENDING') {
      notify.success(`${requestTypeLabel(r.reqType)} 신청 ${r.status === 'APPROVED' ? '승인이 근태에 반영되었습니다' : '결과가 반영되었습니다'}`);
      qc.invalidateQueries({ queryKey: attnKeys.all });
    }
    wasWaiting.current = waiting;
  }, [waiting, r, notify, qc]);

  if (q.isPending) return <Skeleton active={false} paragraph={{ rows: 6 }} />;
  if (q.isError) return <ErrorPanel error={q.error} onRetry={() => q.refetch()} />;
  const req = q.data;
  const p = doc.data ? documentPermissions(doc.data, meId) : null;
  // 결재 문서를 못 읽어도 서버가 회수 조건을 검증한다(ATTN_016·017)
  const canCancel = req.status === 'PENDING' && !waiting && (p ? p.canCancel : true);
  const docHref = req.approvalDocId != null ? `/approvals/${req.approvalDocId}` : undefined;

  return (
    <div className={s.detail}>
      <div className={s.head}>
        <b>{requestTypeLabel(req.reqType)}</b>
        <span className="nx-num">{targetText(req)}</span>
        {waiting
          ? <AsyncSync waiting onCheck={() => q.refetch()} checkedAt={q.dataUpdatedAt} delayedHref={docHref} />
          : <RequestStatusCell r={req} />}
      </div>

      <Items r={req} />

      <dl className={s.kv}>
        <div><dt>사유</dt><dd style={{ fontWeight: 400, whiteSpace: 'pre-wrap' }}>{req.reason}</dd></div>
        <div><dt>상신</dt><dd className="nx-num">{formatDateTime(req.createdAt)}</dd></div>
        {req.completedAt && <div><dt>결재 완료 반영</dt><dd className="nx-num">{formatDate(req.completedAt)} {formatTime(req.completedAt, true)}</dd></div>}
        {req.docNo && (
          <div>
            <dt>결재 문서</dt>
            <dd>{docHref ? <Link to={docHref} className="nx-mono">{req.docNo} ↗</Link> : <span className="nx-mono">{req.docNo}</span>}</dd>
          </div>
        )}
      </dl>

      {doc.data && (
        <section className={s.fieldCol}>
          <span className={s.label}>결재선 <StatusBadge domain="DocumentStatus" value={doc.data.status} /></span>
          <ApprovalLine doc={doc.data} meId={meId} />
        </section>
      )}

      <div className={s.foot}>
        {req.status === 'PENDING' && !canCancel && !waiting && <span className={s.muted}>결재가 진행되어 회수할 수 없습니다</span>}
        {(req.status === 'REJECTED' || req.status === 'CANCELED') && (
          <span className={s.muted}>반려·회수된 신청은 다시 신청해야 합니다</span>
        )}
        <span className={s.spacer} />
        {(req.status === 'REJECTED' || req.status === 'CANCELED') && (
          <Link to={retryHref(req)}><Button>같은 내용으로 다시 신청</Button></Link>
        )}
        {canCancel && (
          <ConfirmAction
            title={`${requestTypeLabel(req.reqType)} 신청을 회수합니다`}
            confirmLabel="회수"
            danger
            onConfirm={() => cancel.mutateAsync(req.attReqId).then(
              () => notify.success('신청을 회수했습니다'),
              (e) => notify.error(e, '회수하지 못했습니다'),
            )}
            trigger={(open) => <Button onClick={open} disabled={cancel.isPending}>회수</Button>}
          >
            아직 아무도 결재하지 않은 신청입니다. 회수하면 결재 문서{req.docNo ? ` ${req.docNo}` : ''}도 함께 회수되고 되돌릴 수 없습니다.
          </ConfirmAction>
        )}
      </div>
    </div>
  );
}
