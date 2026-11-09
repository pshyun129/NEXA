import { Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useLeaveGrants, useLeaveLedger, type LeaveGrant, type LeaveLedgerEntry } from '@/api/leave';
import { Card } from '@/ui/primitives';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { formatDate, formatDateTime, formatLeaveDays, kst, serverUtc } from '@/format';
import { leaveGrantKindLabel, leaveLedgerLabel } from '@/status/labels';
import s from './leave.module.css';

/** 증감 표시. 0이면 비운다 */
function Delta({ v }: { v: number }) {
  if (v === 0) return null;
  return <span className={`${s.delta} ${v > 0 ? s.plus : s.minus} nx-num`}>{v > 0 ? '+' : ''}{formatLeaveDays(v)}</span>;
}

/** 발생 내역 + 변동 원장(P1, 설계 9장 "휴가 잔여·신청·내역") */
export function LeaveLedger({ onOpenRequest }: { onOpenRequest: (id: number) => void }) {
  const grants = useLeaveGrants();
  const ledger = useLeaveLedger();

  const grantCols: ColumnsType<LeaveGrant> = [
    { title: '유형', dataIndex: 'balanceTypeName', width: 110 },
    { title: '구분', dataIndex: 'grantKind', width: 110, render: (v: string) => leaveGrantKindLabel(v) },
    { title: '발생', dataIndex: 'originalMinutes', width: 100, align: 'right', render: (v: number) => <span className="nx-num">{formatLeaveDays(v)}</span> },
    { title: '유효 기간', key: 'valid',
      // expiresOn은 만료 시각(그 시각부터 무효)이라 하루 전 날짜까지 쓸 수 있다
      render: (_, g) => (
        <span className="nx-num">
          {formatDate(serverUtc(g.validFrom))} ~ {g.expiresOn ? formatDate(kst(serverUtc(g.expiresOn)).subtract(1, 'minute')) : '기한 없음'}
        </span>
      ) },
  ];

  const ledgerCols: ColumnsType<LeaveLedgerEntry> = [
    { title: '일시', dataIndex: 'createdAt', width: 150, render: (v: string) => <span className="nx-num">{formatDateTime(serverUtc(v), true)}</span> },
    { title: '구분', dataIndex: 'eventType', width: 100, render: (v: string) => leaveLedgerLabel(v) },
    { title: '보유', dataIndex: 'quantityDelta', width: 100, align: 'right', render: (v: number) => <Delta v={v} /> },
    { title: '결재 중', dataIndex: 'reservedDelta', width: 100, align: 'right', render: (v: number) => <Delta v={v} /> },
    { title: '내용', key: 'reason', render: (_, e) => (
      <span>
        {e.reason}
        {e.leaveReqId != null && (
          <> · <a role="button" tabIndex={0} onClick={() => onOpenRequest(e.leaveReqId!)}
                 onKeyDown={(k) => { if (k.key === 'Enter') onOpenRequest(e.leaveReqId!); }}>신청 #{e.leaveReqId}</a></>
        )}
      </span>
    ) },
  ];

  return (
    <div className={s.stack}>
      <Card title="발생 내역" flush>
        {grants.isError ? <ErrorPanel error={grants.error} onRetry={() => grants.refetch()} /> : (
          <Table<LeaveGrant> rowKey="grantId" size="middle" loading={grants.isPending} columns={grantCols}
                             dataSource={grants.data ?? []} pagination={false}
                             locale={{ emptyText: '발생한 휴가가 없습니다' }} />
        )}
      </Card>
      <Card title="변동 내역" flush>
        {ledger.isError ? <ErrorPanel error={ledger.error} onRetry={() => ledger.refetch()} /> : (
          <Table<LeaveLedgerEntry> rowKey="entryId" size="middle" loading={ledger.isPending} columns={ledgerCols}
                                   dataSource={ledger.data ?? []}
                                   pagination={(ledger.data?.length ?? 0) > 20 ? { pageSize: 20, showSizeChanger: false, size: 'small' } : false}
                                   locale={{ emptyText: '변동 내역이 없습니다' }} />
        )}
      </Card>
    </div>
  );
}
