import type { ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Navigate } from 'react-router-dom';
import { useSession } from './session';
import { authApi } from './authApi';
import { api } from '@/api/client';
import { isApiError } from '@/api/ApiError';
import type { AdminLeaveType } from '@/api/leaveAdmin';
import { FullPageError, FullPageNotice } from '@/ui/FullPageNotice';

/** 메뉴의 Role과 분리한다. 최종 허용은 JWT·최신 HR 정보를 확인하는 Leave 서버 응답으로 확인한다. */
export function useLeaveAdministrator() {
  const { me, status } = useSession();
  const qc = useQueryClient();
  return useQuery({
    queryKey: ['leaveAdministrator', me?.empId, me?.dutyId, me?.status],
    enabled: status === 'authenticated' && me != null,
    placeholderData: undefined,
    retry: false,
    staleTime: 0,
    refetchOnWindowFocus: 'always',
    refetchInterval: () => document.hidden ? false : 30_000,
    queryFn: async () => {
      const latest = await authApi.me();
      if (latest.empId !== me?.empId || latest.status !== 'EMPLOYED' || latest.dutyName !== '휴가관리자') return false;
      try {
        // 별도 권한 API가 없으므로 기존 읽기 API로 서버 권한을 확인하고 조회 결과도 재사용한다.
        const types = await api.get<AdminLeaveType[]>('/api/v1/leaves/admin/types');
        qc.setQueryData(['leaveAdmin', 'types'], types);
        return true;
      } catch (error) {
        if (isApiError(error) && (error.status === 401 || error.status === 403)) return false;
        throw error;
      }
    },
  });
}

export function RequireLeaveAdministrator({ children }: { children: ReactNode }) {
  const status = useSession((s) => s.status);
  const access = useLeaveAdministrator();
  if (status !== 'authenticated') return <Navigate to="/login?next=%2Fadmin%2Fleave" replace />;
  if (access.isError) return <FullPageError title="휴가 관리 권한을 확인하지 못했습니다" error={access.error} onRetry={() => access.refetch()} />;
  if (access.isPending || !access.isFetchedAfterMount) return <FullPageNotice text="휴가 관리 권한을 확인하고 있습니다…" />;
  return access.data === true ? <>{children}</> : <Navigate to="/" replace />;
}
