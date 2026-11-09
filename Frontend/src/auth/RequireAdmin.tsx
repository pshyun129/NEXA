import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { useCan } from './useCan';

/** 관리 화면 보호. 실제 권한 검사는 백엔드가 하고, 이건 메뉴·경로 노출만 막는다. */
export function RequireAdmin({ children }: { children: ReactNode }) {
  const can = useCan('admin.view');
  return can ? <>{children}</> : <Navigate to="/" replace />;
}
