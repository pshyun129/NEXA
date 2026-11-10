import { useMemo, useState } from 'react';
import { Alert, Button, Segmented, Select, Skeleton, Space, Table, Tag } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useSearchParams } from 'react-router-dom';
import {
  useAccrualRules, useAdminLeaveTypes, useEmployeeBalances, useEmployeeGrants, useEmployeeLedger, useEmployeePolicyAssignments,
  useLeavePolicies, useLeavePolicy, usePolicyVersion, usePolicyVersions, useTypeRules,
  type AccrualFrequency, type AdminLeaveType, type LeaveAccrualRule, type LeavePolicyVersionSummary, type LeaveTypeRule, type PolicyAssignment,
} from '@/api/leaveAdmin';
import type { AllowedUnits } from '@/api/leave';
import { useAllEmployees } from '@/api/org';
import { Card, DotChip, EmptyState, PageHeader } from '@/ui/primitives';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { Icon } from '@/ui/Icon';
import { CalendarDays, Users } from '@/ui/icons';
import { BalanceItem } from '@/leave/Balances';
import { LedgerTables } from '@/leave/Ledger';
import { formatDate, formatDuration, formatLeaveAmount, serverUtc, nowKst, kst } from '@/format';
import { PolicyNameAction, VersionEditor, VersionDecision, AccrualEditor, TypeRuleEditor } from '@/leave/admin/PolicyActions';
import { LeaveTypeEditor, DisableLeaveType } from '@/leave/admin/TypeActions';
import { AssignmentEditor, ManualGrantEditor, AdjustmentEditor } from '@/leave/admin/EmployeeActions';
import { RequestManagement } from '@/leave/admin/RequestManagement';
import s from './leavePolicy.module.css';

type Tab = 'policy' | 'types' | 'employee' | 'requests';

const UNITS: Record<AllowedUnits, string> = { FULL: '종일만', HALF: '반차만', HOUR: '시간 단위', MIXED: '종일·반차·시간' };
const REQUEST_UNIT: Record<AdminLeaveType['requestUnit'], string> = { DAY: '일', HALF_DAY: '반일', HOUR: '시간', MIXED: '혼합' };
const FREQ: Record<AccrualFrequency, string> = { MONTHLY: '매월', YEARLY: '매년', ON_HIRE: '입사 시', CUSTOM: '사용자 정의' };
const EXPIRY: Record<string, string> = { END_OF_VALIDITY: '유효 기간이 끝나면 소멸' };

/** MM-DD → 1월 1일 */
const mmdd = (v: string | null) => (v ? v.split('-').map(Number).map((n, i) => `${n}${i ? '일' : '월'}`).join(' ') : '—');
const period = (from: string | null, to: string | null) => `${from ? formatDate(serverUtc(from)) : '미지정'} ~ ${to ? formatDate(serverUtc(to)) : '계속'}`;
/** 근속 개월 구간: 0~12개월 / 12개월 이상 */
const service = (min: number, max: number | null) => (max == null ? `${min}개월 이상` : `최소 ${min}개월 · 최대 ${max}개월`);

/* ---------------- 정책 ---------------- */

function VersionDetail({ policyId, versionId, typeNames, masters, onDeleted }: {
  policyId: number; versionId: number; typeNames: Map<number, string>; masters: AdminLeaveType[]; onDeleted: () => void;
}) {
  const v = usePolicyVersion(policyId, versionId);
  const types = useTypeRules(policyId, versionId);
  const accrual = useAccrualRules(policyId, versionId);

  const typeCols: ColumnsType<LeaveTypeRule> = [
    { title: '휴가 유형', key: 'type', render: (_, r) => typeNames.get(r.leaveTypeId) ?? `유형 #${r.leaveTypeId}` },
    { title: '신청', dataIndex: 'isRequestable', width: 90, render: (on: boolean) => <DotChip tone={on ? 'success' : 'neutral'}>{on ? '가능' : '불가'}</DotChip> },
    { title: '신청 단위', dataIndex: 'allowedUnits', width: 130, render: (u: AllowedUnits) => UNITS[u] ?? u },
    { title: '최소', dataIndex: 'minMinutes', width: 100, align: 'right', render: (m: number | null) => <span className="nx-num">{m == null ? '—' : formatDuration(m)}</span> },
    { title: '최대', dataIndex: 'maxMinutes', width: 100, align: 'right', render: (m: number | null) => <span className="nx-num">{m == null ? '—' : formatLeaveAmount(m)}</span> },
  ];
  const accrualCols: ColumnsType<LeaveAccrualRule> = [
    { title: '근속 구간', key: 'svc', render: (_, r) => service(r.minServiceMonths, r.maxServiceMonths) },
    { title: '주기', dataIndex: 'frequency', width: 100, render: (f: AccrualFrequency) => FREQ[f] ?? f },
    { title: '발생', dataIndex: 'grantMinutes', width: 100, align: 'right', render: (m: number) => <span className="nx-num">{formatLeaveAmount(m)}</span> },
    { title: '상태', dataIndex: 'isActive', width: 90, render: (on: boolean) => <DotChip tone={on ? 'success' : 'neutral'}>{on ? '사용' : '꺼짐'}</DotChip> },
  ];

  return (
    <div className={s.stack}>
      {v.data && !v.isError && <Space wrap><VersionEditor policyId={policyId} version={v.data} />
        <VersionDecision policyId={policyId} version={v.data} onDeleted={onDeleted} /></Space>}
      {v.data?.isActive && <Alert type="info" message="현재 활성 버전은 읽기 전용입니다. 설정 변경은 새 버전을 만든 뒤 진행하세요." />}
      <Card title="버전 설정">
        {v.isError ? <ErrorPanel error={v.error} onRetry={() => v.refetch()} />
          : !v.data ? <Skeleton active={false} paragraph={{ rows: 3 }} />
          : (
            <dl className={s.kv}>
              <dt>발생 기준</dt><dd>{v.data.baseType === 'FISCAL_YEAR' ? `회계연도(매년 ${mmdd(v.data.fiscalStart)} 시작)` : '입사일'}</dd>
              <dt>계산 단위</dt><dd>{formatDuration(v.data.unitMinutes)}</dd>
              <dt>이월</dt>
              <dd>{!v.data.carryOverAllowed ? '이월하지 않음' : v.data.carryOverLimitMinutes != null ? `최대 ${formatLeaveAmount(v.data.carryOverLimitMinutes)}` : '제한 없음'}</dd>
              <dt>소멸</dt><dd>{EXPIRY[v.data.expiryRule] ?? v.data.expiryRule}</dd>
              <dt>적용 기간</dt><dd className="nx-num">{period(v.data.effectiveFrom, v.data.effectiveTo)}</dd>
              <dt>만든 날</dt><dd className="nx-num">{formatDate(serverUtc(v.data.createdAt))}</dd>
            </dl>
          )}
      </Card>
      <Card title="발생 규칙" meta={<AccrualEditor policyId={policyId} versionId={versionId} rules={accrual.data ?? []}
        disabled={!v.data || v.isError || v.data.isActive || accrual.isError || !accrual.isSuccess} />} flush>
        {accrual.isError ? <ErrorPanel error={accrual.error} onRetry={() => accrual.refetch()} /> : (
          <Table<LeaveAccrualRule> rowKey="ruleId" size="middle" loading={accrual.isPending} columns={accrualCols}
                                   dataSource={accrual.data ?? []} pagination={false} tableLayout="fixed"
                                   locale={{ emptyText: '발생 규칙이 없습니다' }} />
        )}
      </Card>
      <Card title="유형별 신청 규칙" meta={<TypeRuleEditor policyId={policyId} versionId={versionId} types={masters}
        rules={types.data ?? []} disabled={!v.data || v.isError || v.data.isActive || types.isError || !types.isSuccess} />} flush>
        {types.isError ? <ErrorPanel error={types.error} onRetry={() => types.refetch()} /> : (
          <Table<LeaveTypeRule> rowKey="ruleId" size="middle" loading={types.isPending} columns={typeCols}
                                dataSource={types.data ?? []} pagination={false} tableLayout="fixed"
                                locale={{ emptyText: '이 버전에 정한 유형 규칙이 없습니다' }} />
        )}
      </Card>
    </div>
  );
}

function PolicyTab({ typeNames, masters }: { typeNames: Map<number, string>; masters: AdminLeaveType[] }) {
  const [params, setParams] = useSearchParams();
  const policies = useLeavePolicies();
  const policyId = Number(params.get('policy')) || policies.data?.[0]?.policyId || null;
  const policy = useLeavePolicy(policyId);
  const versions = usePolicyVersions(policyId);
  const sorted = useMemo(() => [...(versions.data ?? [])].sort((a, b) => b.versionNo - a.versionNo), [versions.data]);
  const versionId = Number(params.get('version')) || (sorted.find((v) => v.isActive) ?? sorted[0])?.policyVersionId || null;

  const set = (key: 'policy' | 'version', value: number) => {
    const next = new URLSearchParams(params);
    next.set(key, String(value));
    if (key === 'policy') next.delete('version');
    setParams(next, { replace: true });
  };

  if (policies.isError) return <ErrorPanel error={policies.error} onRetry={() => policies.refetch()} />;
  if (!policies.data) return <Skeleton active={false} paragraph={{ rows: 5 }} />;
  if (policies.data.length === 0) return <Space direction="vertical"><PolicyNameAction onCreated={(id) => set('policy', id)} /><EmptyState icon={CalendarDays} title="등록된 휴가 정책이 없습니다" /></Space>;

  return (
    <div className={s.policyLayout}>
      <aside className={s.side}>
        <div className={s.sideLabel}>정책 {policies.data.length}</div>
        <PolicyNameAction onCreated={(id) => set('policy', id)} />
        <ul className={s.policyList}>
          {policies.data.map((p) => (
            <li key={p.policyId}>
              <button type="button" className={p.policyId === policyId ? s.policyOn : ''} onClick={() => set('policy', p.policyId)}
                      aria-current={p.policyId === policyId ? 'true' : undefined}>
                <Icon as={CalendarDays} size={16} />
                <span>{p.name}</span>
              </button>
            </li>
          ))}
        </ul>
      </aside>
      <div className={s.main}>
        <div className={s.versionBar}>
          <strong>{policies.data.find((p) => p.policyId === policyId)?.name}</strong>
          {policy.isError && <ErrorPanel error={policy.error} onRetry={() => policy.refetch()} />}
          {policy.data && !policy.isError && <PolicyNameAction key={policyId} policy={policy.data} />}
          {policyId != null && <VersionEditor key={policyId} policyId={policyId} onSaved={(id) => set('version', id)} />}
          {versions.isError ? <ErrorPanel error={versions.error} onRetry={() => versions.refetch()} />
            : !versions.data ? <Skeleton.Button active={false} size="small" />
            : sorted.length === 0 ? <span className={s.muted}>버전이 없습니다</span>
            : (
              <div className={s.versions} role="tablist" aria-label="정책 버전">
                {sorted.map((v: LeavePolicyVersionSummary) => (
                  <button key={v.policyVersionId} type="button" role="tab" aria-selected={v.policyVersionId === versionId}
                          className={v.policyVersionId === versionId ? s.versionOn : ''} onClick={() => set('version', v.policyVersionId)}>
                    v{v.versionNo}
                    {v.isActive && <Tag color="green" bordered={false} className={s.activeTag}>적용 중</Tag>}
                    <small className="nx-num">{formatDate(serverUtc(v.effectiveFrom))}~</small>
                  </button>
                ))}
              </div>
            )}
        </div>
        {policyId != null && versionId != null && <VersionDetail key={`${policyId}:${versionId}`} policyId={policyId} versionId={versionId} typeNames={typeNames} masters={masters}
          onDeleted={() => { const next = new URLSearchParams(params); next.delete('version'); setParams(next, { replace: true }); }} />}
      </div>
    </div>
  );
}

/* ---------------- 휴가 유형 ---------------- */

function TypesTab({ q }: { q: ReturnType<typeof useAdminLeaveTypes> }) {
  const cols: ColumnsType<AdminLeaveType> = [
    { title: '이름', dataIndex: 'name', ellipsis: true },
    { title: '급여', dataIndex: 'paid', width: 90, render: (v: boolean) => (v ? '유급' : '무급') },
    { title: '잔액 차감', key: 'bal', width: 160, render: (_, t) => (t.balanceMode === 'NONE' ? <span className={s.muted}>차감 없음</span> : t.balanceType?.name ?? '—') },
    { title: '신청 단위', dataIndex: 'requestUnit', width: 110, render: (u: AdminLeaveType['requestUnit']) => REQUEST_UNIT[u] ?? u },
    { title: '1회 최대', dataIndex: 'maxMinutes', width: 110, align: 'right', render: (m: number | null) => <span className="nx-num">{m == null ? '—' : formatLeaveAmount(m)}</span> },
    { title: '상태', dataIndex: 'isActive', width: 90, render: (on: boolean) => <DotChip tone={on ? 'success' : 'neutral'}>{on ? '사용' : '꺼짐'}</DotChip> },
    { title: '관리', key: 'actions', width: 190, render: (_, type) => <Space><LeaveTypeEditor type={type} types={q.data ?? []} /><DisableLeaveType type={type} /></Space> },
  ];
  return (
    <Card title="휴가 유형" meta={<LeaveTypeEditor types={q.data ?? []} />} flush>
      {q.isError ? <ErrorPanel error={q.error} onRetry={() => q.refetch()} /> : (
        <Table<AdminLeaveType> rowKey="leaveTypeId" size="middle" loading={q.isPending} columns={cols} dataSource={q.data ?? []}
                               pagination={false} tableLayout="fixed" locale={{ emptyText: '등록된 휴가 유형이 없습니다' }} />
      )}
    </Card>
  );
}

/* ---------------- 사원별 휴가 ---------------- */

function EmployeeLeave({ empId }: { empId: number }) {
  const [operation, setOperation] = useState<'grant' | 'adjust' | null>(null);
  const masters = useAdminLeaveTypes();
  const balances = useEmployeeBalances(empId);
  const grants = useEmployeeGrants(empId);
  const ledger = useEmployeeLedger(empId);
  const assigns = useEmployeePolicyAssignments(empId);
  const now = nowKst();
  const current = (assigns.data ?? []).filter((a) => !kst(a.effectiveFrom).isAfter(now) && (!a.effectiveTo || kst(a.effectiveTo).isAfter(now)));
  const currentVersions = usePolicyVersions(current.length === 1 ? current[0].policyId : null);
  const assignCols: ColumnsType<PolicyAssignment> = [
    { title: '정책', dataIndex: 'policyName' },
    { title: '적용 기간', key: 'p', render: (_, a) => <span className="nx-num">{period(a.effectiveFrom, a.effectiveTo)}</span> },
  ];
  return (
    <div className={s.stack}>
      <Space wrap><AssignmentEditor employeeId={empId} />
        <Button disabled={!masters.isSuccess} onClick={() => setOperation('grant')}>수동 휴가 지급</Button>
        <Button disabled={!grants.isSuccess || !balances.isSuccess} onClick={() => setOperation('adjust')}>잔액 증감 조정</Button></Space>
      {assigns.isSuccess && <Alert type={current.length > 1 ? 'error' : 'info'} message={current.length === 1
        ? `현재 배정: ${current[0].policyName} · 활성 버전 ${currentVersions.data?.find((v) => v.isActive)?.versionNo ?? '확인 중/없음'}`
        : current.length === 0 ? '현재 시점의 정책 배정이 없습니다' : '현재 적용 기간이 겹칩니다. 서버 정합성 확인이 필요합니다'} />}
      <p className={s.muted}>현재 배정 표시는 조회한 이력과 브라우저 현재 시각 기준입니다. 지급·신청 시 서버가 최신 배정과 활성 버전을 다시 검증합니다. 관리자 작업은 원장의 지급·조정·복원 사유로 확인하세요.</p>
      <Card title="잔액">
        {balances.isError ? <ErrorPanel error={balances.error} onRetry={() => balances.refetch()} />
          : !balances.data ? <Skeleton active={false} paragraph={{ rows: 2 }} />
          : balances.data.length === 0 ? <EmptyState title="휴가 잔액이 없습니다" description="정책이 배정되고 휴가가 발생하면 보입니다" />
          : <div className={s.balances}>{balances.data.map((b) => <BalanceItem key={b.accountId} b={b} />)}</div>}
      </Card>
      <Card title="정책 배정" flush>
        {assigns.isError ? <ErrorPanel error={assigns.error} onRetry={() => assigns.refetch()} /> : (
          <Table<PolicyAssignment> rowKey="assignmentId" size="middle" loading={assigns.isPending} columns={assignCols}
                                   dataSource={assigns.data ?? []} pagination={false} locale={{ emptyText: '배정된 정책이 없습니다' }} />
        )}
      </Card>
      <LedgerTables grants={grants} ledger={ledger} />
      {operation === 'grant' && <ManualGrantEditor employeeId={empId} types={masters.data ?? []} onClose={() => setOperation(null)} />}
      {operation === 'adjust' && <AdjustmentEditor employeeId={empId} grants={grants.data ?? []} balances={balances.data ?? []} onClose={() => setOperation(null)} />}
    </div>
  );
}

function EmployeeTab() {
  const [params, setParams] = useSearchParams();
  const emps = useAllEmployees();
  const empId = Number(params.get('emp')) || null;
  const options = useMemo(() => (emps.data ?? []).map((e) => ({
    value: e.empId,
    label: `${e.name}${e.empNo ? ` (${e.empNo})` : ''}${e.deptName ? ` · ${e.deptName}` : ''}`,
  })), [emps.data]);
  const pick = (id: number) => { const next = new URLSearchParams(params); next.set('emp', String(id)); setParams(next, { replace: true }); };
  const picked = emps.data?.find((e) => e.empId === empId);

  return (
    <div className={s.stack}>
      <div className={s.empBar}>
        <Select<number> showSearch size="large" placeholder="사원 이름·사번·부서로 찾기" style={{ width: 420, maxWidth: '100%' }}
                        loading={emps.isPending} value={empId ?? undefined} onChange={pick} options={options} optionFilterProp="label"
                        aria-label="사원 선택" />
        {picked && <span className={s.muted}>{[picked.deptName, picked.positionName].filter(Boolean).join(' · ')}{picked.hireDate && ` · 입사 ${formatDate(picked.hireDate)}`}</span>}
      </div>
      {emps.isError ? <ErrorPanel error={emps.error} onRetry={() => emps.refetch()} />
        : empId == null ? <EmptyState icon={Users} title="사원을 고르면 잔액·발생·변동 내역을 볼 수 있어요" />
        : <EmployeeLeave key={empId} empId={empId} />}
    </div>
  );
}

/**
 * 휴가 직책 관리자 전용: 기존 조회 구조에 정책·유형·직원별 작업과 신청 관리를 연결한다.
 */
export function LeavePolicyPage() {
  const [params, setParams] = useSearchParams();
  const tab = (params.get('tab') as Tab | null) ?? 'policy';
  const types = useAdminLeaveTypes();
  const typeNames = useMemo(() => new Map((types.data ?? []).map((t) => [t.leaveTypeId, t.name])), [types.data]);
  const setTab = (t: Tab) => setParams(new URLSearchParams({ tab: t }), { replace: true });

  return (
    <div className={s.stack}>
      <PageHeader eyebrow="ADMIN · LEAVE" title="휴가 관리" description="정책·휴가 유형·직원별 지급과 잔액, 승인 휴가 취소를 관리합니다." />
      <Segmented<Tab> value={tab} onChange={setTab} className={s.tabs}
                      options={[{ label: '정책', value: 'policy' }, { label: '휴가 유형', value: 'types' }, { label: '사원별 휴가', value: 'employee' }, { label: '신청 관리', value: 'requests' }]} />
      {tab === 'policy' ? <PolicyTab typeNames={typeNames} masters={types.data ?? []} /> : tab === 'types' ? <TypesTab q={types} /> : tab === 'requests' ? <RequestManagement /> : <EmployeeTab />}
    </div>
  );
}
