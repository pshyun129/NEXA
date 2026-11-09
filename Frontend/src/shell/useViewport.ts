import { useEffect, useState } from 'react';

/** 반응형 구간(style-guide 5.4): ≥1280 / 1152~1279 / 1024~1151 / <1024 */
export type Band = 'wide' | 'demo' | 'compact' | 'narrow';

const band = (w: number): Band => (w >= 1280 ? 'wide' : w >= 1152 ? 'demo' : w >= 1024 ? 'compact' : 'narrow');

export function useViewport() {
  const [width, setWidth] = useState(() => window.innerWidth);
  useEffect(() => {
    const on = () => setWidth(window.innerWidth);
    window.addEventListener('resize', on);
    return () => window.removeEventListener('resize', on);
  }, []);
  return { width, band: band(width) };
}
