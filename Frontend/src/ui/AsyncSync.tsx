import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { usePollWindow } from '@/common/usePollWindow';
import { formatCheckedAgo } from '@/format';
import { StatusBadge } from './StatusBadge';

const INTERVAL_MS = 5_000;

interface Props {
  /** 결재는 끝났는데 원본(신청)에 아직 반영되지 않은 상태 */
  waiting: boolean;
  /** 다시 확인(쿼리 refetch) */
  onCheck: () => void;
  /** 마지막으로 확인한 시각(dataUpdatedAt, ms) */
  checkedAt: number;
  /** 반영 지연 시 이동할 결재 문서 */
  delayedHref?: string;
  /** 호출 측 Query가 폴링하면 중복 타이머를 만들지 않는다. */
  autoCheck?: boolean;
}

/**
 * 비동기 반영 칩(설계 7.3). Kafka 전파가 필요한 경우에만 쓴다.
 * 5초 간격 + 창 포커스로 확인하고, 2분이 지나도 그대로면 멈추고 "반영 지연"으로 바꾼다.
 * 반영되면 칩이 사라지고 호출 측이 최종 배지를 그린다. 낙관적으로 미리 바꾸지 않는다.
 */
export function AsyncSync({ waiting, onCheck, checkedAt, delayedHref, autoCheck = true }: Props) {
  const poll = usePollWindow(waiting);
  const [now, setNow] = useState(Date.now());

  useEffect(() => {
    if (!poll.active || !autoCheck) return;
    const check = () => { if (!document.hidden) onCheck(); };
    const t = window.setInterval(check, INTERVAL_MS);
    window.addEventListener('focus', check);
    return () => { window.clearInterval(t); window.removeEventListener('focus', check); };
  }, [poll.active, onCheck, autoCheck]);

  // "n초 전 확인" 표시용 1초 틱
  useEffect(() => {
    if (!poll.active) return;
    const t = window.setInterval(() => setNow(Date.now()), 1_000);
    return () => window.clearInterval(t);
  }, [poll.active]);

  if (!waiting) return null;
  if (poll.expired) {
    return (
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
        <StatusBadge domain="AsyncSync" value="DELAYED" />
        {delayedHref && <Link to={delayedHref}>결재 문서에서 확인 ↗</Link>}
      </span>
    );
  }
  return (
    <span role="status" aria-live="polite">
      <StatusBadge domain="AsyncSync" value="SYNCING" label={`반영 중 · ${formatCheckedAgo((now - checkedAt) / 1000)}`} />
    </span>
  );
}
