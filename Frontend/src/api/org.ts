import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import type { Employee } from './types';

// user-service 부서 관리(DepartmentAdminController, /api/v1/admin/departments, ADMIN)와 사원 목록(origin/develop def2081 기준).

export type DeptType = 'COMPANY' | 'DIVISION' | 'TEAM';
export const DEPT_TYPE_LABEL: Record<DeptType, string> = { COMPANY: '회사', DIVISION: '본부', TEAM: '팀' };

/** headEmpId는 저장값 그대로라 부서장이 휴직·퇴직이어도 남을 수 있다(결재선에서는 공석으로 본다) */
export interface Department {
  deptId: number; name: string; sortOrder: number | null; parentDeptId: number | null;
  deptType: DeptType | null; headEmpId: number | null;
}

const DEPT_BASE = '/api/v1/admin/departments';
const deptKey = ['admin', 'departments'] as const;
const allEmpKey = ['employees', 'all'] as const;

export function useDepartments() {
  return useQuery({ queryKey: deptKey, queryFn: () => api.get<Department[]>(DEPT_BASE) });
}

/** 전체 사원(퇴직자 포함, 사원 ID 순). deptId 필터는 하위 부서를 포함하지 않아 전체를 받아 화면에서 거른다 */
export function useAllEmployees(enabled = true) {
  return useQuery({
    queryKey: allEmpKey,
    queryFn: () => api.get<{ employees: Employee[] }>('/api/v1/employees').then((r) => r.employees),
    enabled,
    staleTime: 60_000,
  });
}

/** 부서장·유형 변경 후 부서 목록과 조직 체인을 다시 받는다 */
function useRefreshOrg() {
  const qc = useQueryClient();
  return () => Promise.all([
    qc.invalidateQueries({ queryKey: deptKey }),
    qc.invalidateQueries({ queryKey: ['org-chain'] }),
  ]);
}

/** 부서장 지정·교체. 재직자만(409 DEPT_002), 그 부서나 하위 부서 소속만(400 DEPT_003) */
export function useAssignHead() {
  const refresh = useRefreshOrg();
  return useMutation({
    mutationFn: ({ deptId, empId }: { deptId: number; empId: number }) => api.put<Department>(`${DEPT_BASE}/${deptId}/head`, { empId }),
    onSettled: refresh,
  });
}

export function useClearHead() {
  const refresh = useRefreshOrg();
  return useMutation({
    mutationFn: (deptId: number) => api.delete<Department>(`${DEPT_BASE}/${deptId}/head`),
    onSettled: refresh,
  });
}

/** 유형 변경. 회사는 최상위만, 상위보다 높거나 하위보다 낮은 단계 불가(409 DEPT_004) */
export function useChangeDeptType() {
  const refresh = useRefreshOrg();
  return useMutation({
    mutationFn: ({ deptId, deptType }: { deptId: number; deptType: DeptType }) =>
      api.put<Department>(`${DEPT_BASE}/${deptId}/type`, { deptType }),
    onSettled: refresh,
  });
}

/**
 * 사원의 소속 부서 → 최상위 부서 체인(GET /employees/{id}/org-chain, 일반 사원도 호출 가능).
 * headEmpId는 재직 중인 부서장만 담고, 공석·휴직·퇴직이면 null(결재선은 위로 올라간다)
 */
export interface OrgUnit { deptId: number; deptName: string; deptType: DeptType | null; headEmpId: number | null }
export interface OrgChain { employeeId: number; deptId: number; chain: OrgUnit[] }

export function useOrgChain(empId: number | null) {
  return useQuery({
    queryKey: ['org-chain', empId],
    queryFn: () => api.get<OrgChain>(`/api/v1/employees/${empId}/org-chain`),
    enabled: empId != null,
  });
}

/**
 * 퇴직 처리(PATCH /employees/{id}/terminate, ADMIN). 즉시 TERMINATED가 되고 refresh 토큰이 지워진다
 * (access는 최대 15분 유효). 부서장 지정은 그대로 남지만 결재선에서는 공석으로 본다. 이미 퇴직이면 409 EMP_008
 */
export function useTerminateEmployee() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ empId, resignDate, resignReason }: { empId: number; resignDate: string; resignReason?: string }) =>
      api.patch<null>(`/api/v1/employees/${empId}/terminate`, { resignDate, resignReason: resignReason || null }),
    onSettled: (_d, _e, v) => Promise.all([
      qc.invalidateQueries({ queryKey: allEmpKey }),
      qc.invalidateQueries({ queryKey: ['employee', v.empId] }),
      qc.invalidateQueries({ queryKey: ['org-chain'] }),
    ]),
  });
}
