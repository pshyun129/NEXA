import { Skeleton } from 'antd';
import { useReconciliation, type MigrationDetail } from '@/api/migration';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { Icon } from '@/ui/Icon';
import { Check, TriangleAlert } from '@/ui/icons';
import { formatNumber } from '@/format';
import { batchEntityLabel } from './schemas';
import s from './migration.module.css';

/** ⑥ 대사(설계 6.7): 적재된 묶음마다 "원본 N = 적재 N ✓". 같지 않으면 warning으로 차이를 보여 준다 */
export function Reconciliation({ d }: { d: MigrationDetail }) {
  const anyLoaded = d.batches.some((b) => b.status === 'LOADED');
  const q = useReconciliation(d.migrationId, anyLoaded);
  if (!anyLoaded) return <span className={s.muted}>적재한 뒤에 원본 행 수와 적재된 수를 비교합니다.</span>;
  if (q.isError) return <ErrorPanel error={q.error} onRetry={() => q.refetch()} />;
  if (!q.data) return <Skeleton active={false} paragraph={{ rows: 2 }} />;
  if (q.data.length === 0) return <span className={s.muted}>비교할 적재 묶음이 없습니다.</span>;
  return (
    <ul className={s.recon}>
      {q.data.map((r) => (
        <li key={`${r.batchId}-${r.metric}`} className={r.match ? s.reconOk : s.reconBad}>
          <b>{batchEntityLabel(r.entity)}</b>
          <span className="nx-num">원본 {formatNumber(r.source)} {r.match ? '=' : '≠'} 적재 {formatNumber(r.loaded)}</span>
          {r.match
            ? <Icon as={Check} size={16} label="일치" />
            : <><Icon as={TriangleAlert} size={16} label="불일치" /><span>차이 {formatNumber(Math.abs(r.source - r.loaded))}행</span></>}
          <span className={`${s.muted} nx-mono`}>{r.batchId}</span>
        </li>
      ))}
    </ul>
  );
}
