import { Skeleton } from 'antd';
import { isApiError } from '@/api/ApiError';
import { useMyLeavePolicy } from '@/api/leave';
import { Card } from '@/ui/primitives';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { formatDate, formatDuration, formatLeaveAmount, serverUtc } from '@/format';
import s from './leave.module.css';

/** MM-DD → 1월 1일 */
const mmdd = (v: string | null) => (v ? v.split('-').map(Number).map((n, i) => `${n}${i ? '일' : '월'}`).join(' ') : '—');

const EXPIRY: Record<string, string> = { END_OF_VALIDITY: '유효 기간이 끝나면 소멸' };

/** 내 휴가 정책(요구사항 2장 휴가 "내 정책"). 배정이 없으면 잔여 카드가 이미 안내하므로 숨긴다 */
export function PolicyCard() {
  const q = useMyLeavePolicy();
  if (isApiError(q.error) && q.error.code === 'LEAVE_010') return null;
  return (
    <Card title="내 휴가 정책">
      {q.isError ? <ErrorPanel error={q.error} onRetry={() => q.refetch()} />
        : q.isPending ? <Skeleton active={false} paragraph={{ rows: 3 }} />
        : (
          <dl className={s.kv}>
            <dt>정책</dt><dd>{q.data.policyName} <span className={s.muted}>v{q.data.versionNo}</span></dd>
            <dt>발생 기준</dt>
            <dd>{q.data.baseType === 'FISCAL_YEAR'
              ? `회계연도(매년 ${mmdd(q.data.fiscalStart)} 시작)`
              : '입사일'}</dd>
            <dt>계산 단위</dt><dd>{formatDuration(q.data.unitMinutes)}</dd>
            <dt>이월</dt>
            <dd>{!q.data.carryOverAllowed ? '이월하지 않음'
              : q.data.carryOverLimitMinutes != null ? `최대 ${formatLeaveAmount(q.data.carryOverLimitMinutes)}` : '제한 없음'}</dd>
            <dt>소멸</dt><dd>{EXPIRY[q.data.expiryRule] ?? q.data.expiryRule}</dd>
            <dt>적용 기간</dt>
            <dd className="nx-num">
              {formatDate(serverUtc(q.data.assignmentEffectiveFrom))} ~ {q.data.assignmentEffectiveTo ? formatDate(serverUtc(q.data.assignmentEffectiveTo)) : ''}
            </dd>
          </dl>
        )}
    </Card>
  );
}
