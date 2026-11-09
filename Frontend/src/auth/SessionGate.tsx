import { useEffect, type ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useSession } from './session';
import { authApi } from './authApi';
import { FullPageNotice } from '@/ui/FullPageNotice';

/** GET /employees/me로 신원을 확인한다. 쿠키가 만료됐으면 client의 reissue 흐름이 한 번 처리한다. */
export function SessionGate({ children }: { children: ReactNode }) {
  const { status, setMe, clear } = useSession();
  const location = useLocation();

  useEffect(() => {
    if (status !== 'unknown') return;
    let cancelled = false;
    authApi
      .me()
      .then((me) => { if (!cancelled) setMe(me); })
      .catch(() => {
        // 401(재발급 실패, 퇴직 AUTH_006 포함)이든 다른 오류든 신원을 확인할 수 없으므로 로그인으로 보낸다.
        if (!cancelled) clear();
      });
    return () => { cancelled = true; };
  }, [status, setMe, clear]);

  if (status === 'anonymous') {
    const next = location.pathname + location.search;
    return <Navigate to={`/login${next && next !== '/' ? `?next=${encodeURIComponent(next)}` : ''}`} replace />;
  }
  if (status !== 'authenticated') return <FullPageNotice />;
  return <>{children}</>;
}
