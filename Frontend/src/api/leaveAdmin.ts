import { useQuery } from '@tanstack/react-query';
import { api } from './client';
import type { AllowedUnits, LeaveBalance, LeaveGrant, LeaveLedgerEntry } from './leave';

// leave-service 관리자 조회 API(/api/v1/leaves/admin/**). frontend-requirements 4.3 범위대로 조회만 붙인다.
// 등록·수정(정책·버전·규칙 PUT/POST, 수동 지급·조정)은 화면에 두지 않는다. 수량은 모두 분 단위(하루 480분).

const ADMIN = '/api/v1/leaves/admin';

export type PolicyBaseType = 'HIRE_DATE' | 'FISCAL_YEAR';
export type AccrualFrequency = 'MONTHLY' | 'YEARLY' | 'ON_HIRE';

export interface LeavePolicy { policyId: number; name: string }

export interface LeavePolicyVersionSummary {
  policyVersionId: number;
  versionNo: number;
  baseType: PolicyBaseType;
  effectiveFrom: string;
  effectiveTo: string | null;
  isActive: boolean;
}

export interface LeavePolicyVersion extends LeavePolicyVersionSummary {
  policyId: number;
  /** FISCAL_YEAR일 때 MM-DD */
  fiscalStart: string | null;
  unitMinutes: number;
  carryOverAllowed: boolean;
  carryOverLimitMinutes: number | null;
  expiryRule: string;
  createdAt: string;
}

export interface LeaveTypeRule {
  ruleId: number;
  policyVersionId: number;
  leaveTypeId: number;
  isRequestable: boolean;
  allowedUnits: AllowedUnits;
  minMinutes: number | null;
  maxMinutes: number | null;
}

export interface LeaveAccrualRule {
  ruleId: number;
  policyVersionId: number;
  minServiceMonths: number;
  maxServiceMonths: number | null;
  frequency: AccrualFrequency;
  grantMinutes: number;
  isActive: boolean;
}

export interface AdminLeaveType {
  leaveTypeId: number;
  name: string;
  paid: boolean;
  balanceMode: 'ACCOUNT' | 'NONE';
  requestUnit: 'DAY' | 'HALF_DAY' | 'HOUR' | 'MIXED';
  maxMinutes: number | null;
  balanceType: { balanceTypeId: number; name: string; active: boolean } | null;
  isActive: boolean;
}

export interface PolicyAssignment {
  assignmentId: number;
  employeeId: number;
  policyId: number;
  policyName: string;
  effectiveFrom: string;
  effectiveTo: string | null;
}

const STALE = 60_000;

export function useLeavePolicies() {
  return useQuery({ queryKey: ['leaveAdmin', 'policies'], queryFn: () => api.get<LeavePolicy[]>(`${ADMIN}/policies`), staleTime: STALE });
}

export function usePolicyVersions(policyId: number | null) {
  return useQuery({
    queryKey: ['leaveAdmin', 'versions', policyId],
    queryFn: () => api.get<LeavePolicyVersionSummary[]>(`${ADMIN}/policies/${policyId}/versions`),
    enabled: policyId != null, staleTime: STALE,
  });
}

export function usePolicyVersion(policyId: number | null, versionId: number | null) {
  return useQuery({
    queryKey: ['leaveAdmin', 'version', policyId, versionId],
    queryFn: () => api.get<LeavePolicyVersion>(`${ADMIN}/policies/${policyId}/versions/${versionId}`),
    enabled: policyId != null && versionId != null, staleTime: STALE,
  });
}

export function useTypeRules(policyId: number | null, versionId: number | null) {
  return useQuery({
    queryKey: ['leaveAdmin', 'typeRules', policyId, versionId],
    queryFn: () => api.get<LeaveTypeRule[]>(`${ADMIN}/policies/${policyId}/versions/${versionId}/type-rules`),
    enabled: policyId != null && versionId != null, staleTime: STALE,
  });
}

export function useAccrualRules(policyId: number | null, versionId: number | null) {
  return useQuery({
    queryKey: ['leaveAdmin', 'accrualRules', policyId, versionId],
    queryFn: () => api.get<LeaveAccrualRule[]>(`${ADMIN}/policies/${policyId}/versions/${versionId}/accrual-rules`),
    enabled: policyId != null && versionId != null, staleTime: STALE,
  });
}

export function useAdminLeaveTypes() {
  return useQuery({ queryKey: ['leaveAdmin', 'types'], queryFn: () => api.get<AdminLeaveType[]>(`${ADMIN}/types`), staleTime: STALE });
}

const emp = (id: number | null) => `${ADMIN}/employees/${id}`;

export function useEmployeeBalances(empId: number | null) {
  return useQuery({ queryKey: ['leaveAdmin', 'emp', empId, 'balances'], queryFn: () => api.get<LeaveBalance[]>(`${emp(empId)}/balances`), enabled: empId != null });
}
export function useEmployeeGrants(empId: number | null) {
  return useQuery({ queryKey: ['leaveAdmin', 'emp', empId, 'grants'], queryFn: () => api.get<LeaveGrant[]>(`${emp(empId)}/grants`), enabled: empId != null });
}
export function useEmployeeLedger(empId: number | null) {
  return useQuery({ queryKey: ['leaveAdmin', 'emp', empId, 'ledger'], queryFn: () => api.get<LeaveLedgerEntry[]>(`${emp(empId)}/ledger`), enabled: empId != null });
}
export function useEmployeePolicyAssignments(empId: number | null) {
  return useQuery({
    queryKey: ['leaveAdmin', 'emp', empId, 'assignments'],
    queryFn: () => api.get<PolicyAssignment[]>(`${emp(empId)}/policy-assignments`),
    enabled: empId != null,
  });
}
