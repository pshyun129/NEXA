import { Alert, Button, Skeleton } from 'antd';
import { Link } from 'react-router-dom';
import { isApiError } from '@/api/ApiError';
import { useLeaveSummary, useLeaveTypes, type LeaveBalance } from '@/api/leave';
import { BarSegments, EmptyState } from '@/ui/primitives';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { formatLeaveDays } from '@/format';
import { APPLY_PATH } from './links';
import s from './leave.module.css';

/** 정책 배정이 없으면(LEAVE_010) 데이터가 없는 것과 구분해 안내한다(설계 6.3) */
export function useNoPolicy() {
  const types = useLeaveTypes();
  return isApiError(types.error) && types.error.code === 'LEAVE_010';
}

export function NoPolicyNotice() {
  return <Alert type="info" showIcon message="휴가 정책이 배정되지 않았습니다. 인사 담당자에게 문의해 주세요" />;
}

const days = (min: number) => formatLeaveDays(min).replace(/일$/, '');

export function BalanceItem({ b }: { b: LeaveBalance }) {
  return (
    <div className={s.balance}>
      <span className={s.balanceName}>{b.balanceTypeName}</span>
      <span className={`${s.kpi} nx-num`}>{days(b.remainingMinutes)}<span className={s.kpiUnit}>일 사용 가능</span></span>
      <BarSegments
        ariaLabel={`${b.balanceTypeName} 사용 가능 ${formatLeaveDays(b.remainingMinutes)}, 결재 중 ${formatLeaveDays(b.reservedMinutes)}`}
        total={b.quantityMinutes}
        segments={[
          { value: b.remainingMinutes, color: 'var(--nx-brand-600)', label: `사용 가능 ${formatLeaveDays(b.remainingMinutes)}` },
          { value: b.reservedMinutes, color: 'var(--nx-info-fg)', label: `결재 중 ${formatLeaveDays(b.reservedMinutes)}` },
        ]}
      />
      <span className={s.balanceMeta}>
        <span>결재 중 <b className="nx-num">{formatLeaveDays(b.reservedMinutes)}</b></span>
        <span>보유 <b className="nx-num">{formatLeaveDays(b.quantityMinutes)}</b></span>
      </span>
    </div>
  );
}

/** 잔액 유형별 카드(연차, 보상휴가 등). 조회 시점에 유효한 발생분만 센다 */
export function BalanceList() {
  const q = useLeaveSummary();
  const noPolicy = useNoPolicy();
  if (q.isError) return <ErrorPanel error={q.error} onRetry={() => q.refetch()} />;
  if (q.isPending) return <Skeleton active={false} paragraph={{ rows: 2 }} />;
  if (q.data.balances.length === 0) {
    return noPolicy ? <NoPolicyNotice /> : (
      <EmptyState title="사용할 수 있는 휴가가 없습니다" description="휴가가 발생하면 여기에 잔여 일수가 표시됩니다" />
    );
  }
  return <div className={s.balances}>{q.data.balances.map((b) => <BalanceItem key={b.accountId} b={b} />)}</div>;
}

/** 홈 "휴가 잔여" 카드 본문(설계 6.1) */
export function LeaveHomeCard() {
  const q = useLeaveSummary();
  const noPolicy = useNoPolicy();
  if (q.isError) return <ErrorPanel error={q.error} onRetry={() => q.refetch()} />;
  if (q.isPending) return <Skeleton active={false} paragraph={{ rows: 2 }} />;
  const list = q.data.balances;
  if (list.length === 0) {
    return noPolicy ? <NoPolicyNotice /> : <div className={s.muted}>사용할 수 있는 휴가가 없습니다</div>;
  }
  return (
    <div className={s.home}>
      {list.map((b) => <BalanceItem key={b.accountId} b={b} />)}
      <div><Link to={APPLY_PATH}><Button>휴가 신청 →</Button></Link></div>
    </div>
  );
}
