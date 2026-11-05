import { useEffect, useRef, useState } from 'react';

/**
 * 숫자가 바뀌면 이전 값에서 새 값까지 세어 간다(설계 8.1 시그니처 3 "빨간 숫자가 0이 되는 순간").
 * reduced-motion이면 바로 바꾼다. 처음 값은 세지 않는다.
 */
export function useCountUp(value: number, durationMs = 900) {
  const [shown, setShown] = useState(value);
  const from = useRef(value);
  useEffect(() => {
    const start = from.current;
    from.current = value;
    if (start === value) return;
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) { setShown(value); return; }
    const t0 = performance.now();
    let raf = 0;
    const step = (t: number) => {
      const p = Math.min(1, (t - t0) / durationMs);
      const eased = 1 - (1 - p) ** 3;
      setShown(Math.round(start + (value - start) * eased));
      if (p < 1) raf = requestAnimationFrame(step);
    };
    raf = requestAnimationFrame(step);
    return () => cancelAnimationFrame(raf);
  }, [value, durationMs]);
  return shown;
}
