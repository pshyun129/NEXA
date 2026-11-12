import { useMutation, useQuery, useQueryClient, type QueryKey } from '@tanstack/react-query';
import { useRef } from 'react';
import { api } from './client';
import type { AllowedUnits, LeaveBalance, LeaveGrant, LeaveLedgerEntry, LeaveRequestSummary, LeaveRequestDetail } from './leave';

// Leave 관리자 계약. 수량은 분, 시각은 오프셋을 포함한다. 직원 권한은 서버가 최종 검증한다.

const ADMIN = '/api/v1/leaves/admin';

export type PolicyBaseType = 'HIRE_DATE' | 'FISCAL_YEAR';
export type AccrualFrequency = 'MONTHLY' | 'YEARLY' | 'ON_HIRE' | 'CUSTOM';

export interface LeavePolicy { policyId: number; name: string }

export interface LeavePolicyVersionSummary {
  policyVersionId: number;
  versionNo: number;
  baseType: PolicyBaseType;
  effectiveFrom: string | null;
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

export function useLeavePolicy(policyId: number | null) {
  return useQuery({ queryKey: ['leaveAdmin', 'policy', policyId], queryFn: () => api.get<LeavePolicy>(`${ADMIN}/policies/${policyId}`),
    enabled: policyId != null, staleTime: STALE, placeholderData: undefined });
}

export function usePolicyVersions(policyId: number | null) {
  return useQuery({
    queryKey: ['leaveAdmin', 'versions', policyId],
    queryFn: () => api.get<LeavePolicyVersionSummary[]>(`${ADMIN}/policies/${policyId}/versions`),
    enabled: policyId != null, staleTime: STALE, placeholderData: undefined,
  });
}

export function usePolicyVersion(policyId: number | null, versionId: number | null) {
  return useQuery({
    queryKey: ['leaveAdmin', 'version', policyId, versionId],
    queryFn: () => api.get<LeavePolicyVersion>(`${ADMIN}/policies/${policyId}/versions/${versionId}`),
    enabled: policyId != null && versionId != null, staleTime: STALE, placeholderData: undefined,
  });
}

export function useTypeRules(policyId: number | null, versionId: number | null) {
  return useQuery({
    queryKey: ['leaveAdmin', 'typeRules', policyId, versionId],
    queryFn: () => api.get<LeaveTypeRule[]>(`${ADMIN}/policies/${policyId}/versions/${versionId}/type-rules`),
    enabled: policyId != null && versionId != null, staleTime: STALE, placeholderData: undefined,
  });
}

export function useAccrualRules(policyId: number | null, versionId: number | null) {
  return useQuery({
    queryKey: ['leaveAdmin', 'accrualRules', policyId, versionId],
    queryFn: () => api.get<LeaveAccrualRule[]>(`${ADMIN}/policies/${policyId}/versions/${versionId}/accrual-rules`),
    enabled: policyId != null && versionId != null, staleTime: STALE, placeholderData: undefined,
  });
}

export function useAdminLeaveTypes() {
  return useQuery({ queryKey: ['leaveAdmin', 'types'], queryFn: () => api.get<AdminLeaveType[]>(`${ADMIN}/types`), staleTime: STALE });
}

const emp = (id: number | null) => `${ADMIN}/employees/${id}`;

export function useEmployeeBalances(empId: number | null) {
  return useQuery({ queryKey: ['leaveAdmin', 'emp', empId, 'balances'], queryFn: () => api.get<LeaveBalance[]>(`${emp(empId)}/balances`), enabled: empId != null, placeholderData: undefined });
}
export function useEmployeeGrants(empId: number | null) {
  return useQuery({ queryKey: ['leaveAdmin', 'emp', empId, 'grants'], queryFn: () => api.get<AdminGrant[]>(`${emp(empId)}/grants`), enabled: empId != null, placeholderData: undefined });
}

export interface VersionInput {
  baseType: PolicyBaseType;
  fiscalStart: string | null;
  unitMinutes: number;
  carryOverAllowed: boolean;
  carryOverLimitMinutes: number | null;
  expiryRule: string;
  effectiveFrom: string | null;
  effectiveTo: string | null;
}
export interface LeaveTypeInput {
  name: string; isPaid: boolean; balanceMode: 'ACCOUNT' | 'NONE'; balanceTypeId: number | null;
  requestUnit: AdminLeaveType['requestUnit']; maxMinutes: number | null; isActive: boolean;
}
export type AccrualRuleInput = Omit<LeaveAccrualRule, 'policyVersionId' | 'ruleId'> & { ruleId?: number };
export type TypeRuleInput = Pick<LeaveTypeRule, 'isRequestable' | 'allowedUnits' | 'minMinutes' | 'maxMinutes'>;
export interface AssignmentInput { policyId: number; effectiveFrom: string | null; effectiveTo: string | null }
export interface GrantInput {
  balanceTypeId: number; grantMode: 'INITIAL' | 'ADDITIONAL'; grantMinutes: number;
  validFrom: string; expiresOn: string | null; reason: string;
}
export interface AdjustmentInput { accountId: number; grantId: number; quantityDelta: number; reason: string }
export interface AdminGrant extends LeaveGrant { accountId: number; balanceTypeId: number; policyVersionId: number | null }
export interface GrantReceipt {
  accountId: number; grantId: number; ledgerEntryId: number; employeeId: number; balanceTypeId: number;
  policyVersionId: number; grantMode: GrantInput['grantMode']; grantMinutes: number; remainingMinutes: number; asOf: string;
}
export interface AdjustmentReceipt {
  entryId: number; employeeId: number; accountId: number; grantId: number; eventType: 'ADJUST';
  quantityDelta: number; reservedDelta: number; actorEmpId: number; reason: string; createdAt: string;
}
export interface AdminRequest extends LeaveRequestSummary { employeeId: number; policyVersionId: number; updatedAt: string }
export interface AdminRequestDetail extends LeaveRequestDetail { employeeId: number; policyVersionId: number; cancelledBy: number | null; updatedAt: string }
export interface CancellationReceipt {
  requestId: number; status: AdminRequest['status']; approvalDocId: number;
  adminCancelStatus: 'IN_PROGRESS' | 'UNKNOWN' | 'FAILED' | 'COMPLETED'; adminCancelRequestId: string;
  cancelledAt: string | null; cancelledBy: number | null; cancelReason: string | null; message: string;
}

const versionPath = (policyId: number, versionId?: number) => `${ADMIN}/policies/${policyId}/versions${versionId == null ? '' : `/${versionId}`}`;
const idempotency = (key: string) => ({ headers: { 'Idempotency-Key': key } });
const versionBody = (input: Partial<VersionInput>) => Object.fromEntries(
  ['baseType', 'fiscalStart', 'unitMinutes', 'carryOverAllowed', 'carryOverLimitMinutes', 'expiryRule', 'effectiveFrom', 'effectiveTo']
    .filter((key) => Object.prototype.hasOwnProperty.call(input, key)).map((key) => [key, input[key as keyof VersionInput]]));
const validId = (id: unknown) => typeof id === 'number' && Number.isSafeInteger(id) && id > 0;

export const leaveAdminApi = {
  createPolicy: (input: { name: string }) => api.post<LeavePolicy>(`${ADMIN}/policies`, input),
  updatePolicy: (id: number, input: { name: string }) => api.patch<LeavePolicy>(`${ADMIN}/policies/${id}`, input),
  createVersion: (policyId: number, input: VersionInput) => api.post<LeavePolicyVersion>(versionPath(policyId), versionBody(input)),
  updateVersion: (policyId: number, versionId: number, input: Partial<VersionInput>) => api.patch<LeavePolicyVersion>(versionPath(policyId, versionId), versionBody(input)),
  deleteVersion: (policyId: number, versionId: number) => api.delete<void>(versionPath(policyId, versionId)),
  activateVersion: (policyId: number, versionId: number) => api.put<unknown>(`${ADMIN}/policies/${policyId}/active-version`, { versionId }),
  replaceAccrual: (policyId: number, versionId: number, rules: AccrualRuleInput[]) => api.put<LeaveAccrualRule[]>(`${versionPath(policyId, versionId)}/accrual-rules`, {
    rules: rules.map((rule) => ({ ...(rule.ruleId != null ? { ruleId: rule.ruleId } : {}), minServiceMonths: rule.minServiceMonths,
      maxServiceMonths: rule.maxServiceMonths ?? null, frequency: rule.frequency, grantMinutes: rule.grantMinutes, isActive: rule.isActive })),
  }),
  upsertTypeRule: (policyId: number, versionId: number, typeId: number, input: TypeRuleInput) => api.put<LeaveTypeRule>(`${versionPath(policyId, versionId)}/type-rules/${typeId}`,
    { isRequestable: input.isRequestable, allowedUnits: input.allowedUnits, minMinutes: input.minMinutes ?? null, maxMinutes: input.maxMinutes ?? null }),
  createType: (input: LeaveTypeInput) => api.post<AdminLeaveType>(`${ADMIN}/types`, input),
  updateType: (id: number, input: Partial<LeaveTypeInput>) => api.patch<AdminLeaveType>(`${ADMIN}/types/${id}`, input),
  assignPolicy: (employeeId: number, input: AssignmentInput) => api.post<PolicyAssignment>(`${emp(employeeId)}/policy-assignments`, input),
  grant: async (employeeId: number, key: string, input: GrantInput) => {
    const result = await api.post<GrantReceipt>(`${emp(employeeId)}/grants`, input, idempotency(key));
    if (!result || !validId(result.accountId) || !validId(result.grantId) || !validId(result.ledgerEntryId)
        || result.employeeId !== employeeId || result.balanceTypeId !== input.balanceTypeId
        || result.grantMode !== input.grantMode || result.grantMinutes !== input.grantMinutes) {
      throw new Error('지급 응답을 검증하지 못했습니다. 같은 요청 키와 원문을 유지해 확인하세요.');
    }
    return result;
  },
  adjust: async (employeeId: number, key: string, input: AdjustmentInput) => {
    const result = await api.post<AdjustmentReceipt>(`${emp(employeeId)}/adjustments`, input, idempotency(key));
    if (!result || !validId(result.entryId) || result.employeeId !== employeeId || result.accountId !== input.accountId
        || result.grantId !== input.grantId || result.eventType !== 'ADJUST' || result.quantityDelta !== input.quantityDelta
        || result.reservedDelta !== 0 || result.reason !== input.reason) {
      throw new Error('조정 응답을 검증하지 못했습니다. 같은 요청 키와 원문을 유지해 확인하세요.');
    }
    return result;
  },
  cancel: async (requestId: number, reason: string) => {
    const result = await api.post<CancellationReceipt>(`${ADMIN}/requests/${requestId}/cancel`, { reason });
    if (!result || result.requestId !== requestId || typeof result.adminCancelRequestId !== 'string' || !result.adminCancelRequestId
        || !['IN_PROGRESS', 'UNKNOWN', 'FAILED', 'COMPLETED'].includes(result.adminCancelStatus)
        || result.status !== (result.adminCancelStatus === 'COMPLETED' ? 'CANCELLED' : 'APPROVED')) {
      throw new Error('취소 응답을 검증하지 못했습니다. 승인 상태를 변경하지 않고 같은 사유를 유지합니다.');
    }
    return result;
  },
};

/** 작업이 영향을 주는 관리자 캐시만 갱신한다. 본인 카드도 다음 조회에 새 값을 받도록 표시한다. */
export function useLeaveAdminMutation<TInput, TResult>(
  action: (input: TInput) => Promise<TResult>, keys: readonly QueryKey[],
) {
  const qc = useQueryClient();
  const inFlight = useRef<Promise<TResult> | null>(null);
  return useMutation({ mutationFn: (input: TInput) => {
    if (inFlight.current) return inFlight.current;
    inFlight.current = Promise.resolve().then(() => action(input)).then(async (result) => {
      await Promise.all(keys.map((queryKey) => qc.invalidateQueries({ queryKey })));
      return result;
    }).finally(() => { inFlight.current = null; });
    return inFlight.current;
  }, retry: false });
}

export function useAdminRequests(enabled: boolean) {
  // 서버가 전체 배열만 제공한다. 미지원 검색 파라미터나 서버 페이지네이션을 가장하지 않는다.
  return useQuery({ queryKey: ['leaveAdmin', 'requests'], queryFn: () => api.get<AdminRequest[]>(`${ADMIN}/requests`),
    enabled, retry: false, refetchOnWindowFocus: false, placeholderData: undefined });
}
export function useAdminRequest(id: number | null) {
  return useQuery({ queryKey: ['leaveAdmin', 'request', id], queryFn: () => api.get<AdminRequestDetail>(`${ADMIN}/requests/${id}`),
    enabled: id != null, placeholderData: undefined });
}
export function useEmployeeLedger(empId: number | null) {
  return useQuery({ queryKey: ['leaveAdmin', 'emp', empId, 'ledger'], queryFn: () => api.get<LeaveLedgerEntry[]>(`${emp(empId)}/ledger`), enabled: empId != null, placeholderData: undefined });
}
export function useEmployeePolicyAssignments(empId: number | null) {
  return useQuery({
    queryKey: ['leaveAdmin', 'emp', empId, 'assignments'],
    queryFn: () => api.get<PolicyAssignment[]>(`${emp(empId)}/policy-assignments`),
    enabled: empId != null, placeholderData: undefined,
  });
}
