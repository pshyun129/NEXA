import { useEffect, useState, type ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useSession } from './session';
import { authApi } from './authApi';
import { isApiError } from '@/api/ApiError';
import { FullPageError, FullPageNotice } from '@/ui/FullPageNotice';

/** GET /employees/me로 신원을 확인한다. 쿠키가 만료됐으면 client의 reissue 흐름이 한 번 처리한다. */
export function SessionGate({ children }: { children: ReactNode }) {
  const { status, setMe, clear } = useSession();
  const location = useLocation();
  // 401이 아닌 실패(user-service 장애·네트워크). 로그아웃하지 않고 [다시 시도]를 보인다(QA CM-B1/OG-18)
  const [error, setError] = useState<unknown>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (status !== 'unknown') return;
    let cancelled = false;
    authApi
      .me()
      .then((me) => { if (!cancelled) setMe(me); })
      .catch((e) => {
        if (cancelled) return;
        // 401(재발급 실패, 퇴직 AUTH_006 포함)만 신원을 확인할 수 없는 것이다 → 로그인으로
        if (isApiError(e) && e.status === 401) clear();
        else setError(e);
      });
    return () => { cancelled = true; };
  }, [status, setMe, clear, attempt]);

  if (status === 'anonymous') {
    const next = location.pathname + location.search;
    return <Navigate to={`/login${next && next !== '/' ? `?next=${encodeURIComponent(next)}` : ''}`} replace />;
  }
  if (status !== 'authenticated' && error != null) {
    return <FullPageError error={error} title="서버에 연결하지 못했습니다" onRetry={() => { setError(null); setAttempt((n) => n + 1); }} />;
  }
  if (status !== 'authenticated') return <FullPageNotice />;
  return <>{children}</>;
}
