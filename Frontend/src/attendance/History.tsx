import { Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useStatusHistory } from '@/api/attendance';
import type { WorkStatusLog } from '@/api/attendanceRequests';
import { formatDateShort, formatDateTime, formatTime } from '@/format';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { EmpName } from '@/ui/EmpName';
import s from './attendance.module.css';

const SOURCE_LABEL: Record<WorkStatusLog['source'], string> = { SELF: '본인', ADMIN: '관리자', APPROVAL: '결재 반영' };

/**
 * 근무상태 변경 이력(P1, 요구사항 3.2). 최대 31일. 삭제된 기록도 취소선으로 남겨 보여 준다.
 * 시각은 서버가 +09:00 오프셋으로 준다.
 */
export function HistoryView({ from, to }: { from: string; to: string }) {
  const q = useStatusHistory(from, to);
  if (q.isError) return <ErrorPanel error={q.error} onRetry={() => q.refetch()} />;
  const cols: ColumnsType<WorkStatusLog> = [
    { title: '근무일', dataIndex: 'workDate', width: 110, render: (v: string) => <span className="nx-num">{formatDateShort(v)}</span> },
    { title: '시각', dataIndex: 'startAt', width: 90, render: (v: string) => <span className="nx-num">{formatTime(v, true)}</span> },
    {
      title: '상태', dataIndex: 'statusName', width: 140,
      render: (v: string, r) => <>{v}{r.isDeleted && <span className={s.tag} style={{ marginLeft: 6 }}>삭제됨</span>}</>,
    },
    { title: '출처', dataIndex: 'source', width: 90, render: (v: WorkStatusLog['source']) => SOURCE_LABEL[v] ?? v },
    { title: '기록한 사람', dataIndex: 'createdBy', width: 110, render: (v: number | null) => <EmpName id={v} /> },
    { title: '사유', dataIndex: 'reason', render: (v: string | null) => v ?? '' },
    { title: '저장 시각', dataIndex: 'createdAt', width: 130, render: (v: string) => <span className="nx-num">{formatDateTime(v, true)}</span> },
  ];
  return (
    <Table<WorkStatusLog>
      rowKey="logId"
      size="middle"
      loading={q.isPending}
      pagination={(q.data?.length ?? 0) > 50 ? { pageSize: 50, showSizeChanger: false, size: 'small' } : false}
      columns={cols}
      dataSource={q.data}
      rowClassName={(r) => (r.isDeleted ? s.rowDeleted : '')}
      locale={{ emptyText: '이 기간에 상태 변경 기록이 없습니다' }}
    />
  );
}
