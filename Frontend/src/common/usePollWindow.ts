import { useEffect, useState } from 'react';

/** 비동기 반영 폴링 한도(설계 7.3): 2분이 지나도 그대로면 멈춘다 */
export const POLL_LIMIT_MS = 120_000;

/**
 * pending이 true가 된 시점부터 2분 동안 active=true. 2분이 지나면 expired=true.
 * pending이 false가 되면 초기화한다. 폴링 여부만 정하고 데이터는 건드리지 않는다.
 */
export function usePollWindow(pending: boolean) {
  const [since, setSince] = useState<number | null>(null);
  const [, rerender] = useState(0);
  useEffect(() => {
    if (pending && since == null) setSince(Date.now());
    if (!pending && since != null) setSince(null);
  }, [pending, since]);
  useEffect(() => {
    if (since == null) return;
    const left = POLL_LIMIT_MS - (Date.now() - since);
    if (left <= 0) return;
    const t = window.setTimeout(() => rerender((n) => n + 1), left);
    return () => window.clearTimeout(t);
  }, [since]);
  const active = pending && (since == null || Date.now() - since < POLL_LIMIT_MS);
  return { active, expired: pending && since != null && !active, since };
}
