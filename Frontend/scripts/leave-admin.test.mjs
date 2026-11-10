import { test, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import TestRenderer, { act } from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { AxiosError } from 'axios';
import { http } from '../src/api/client.ts';
import { ApiError } from '../src/api/ApiError.ts';
import { useSession } from '../src/auth/session.ts';
import { RequireAdmin } from '../src/auth/RequireAdmin.tsx';
import { RequireLeaveAdministrator, useLeaveAdministrator } from '../src/auth/RequireLeaveAdministrator.tsx';
import { adminNav, leaveAdminNav } from '../src/shell/nav.ts';
import { leaveAdminApi, useLeaveAdminMutation, useEmployeeBalances, usePolicyVersions, useLeavePolicy } from '../src/api/leaveAdmin.ts';
import { usePersistentAdminRequest } from '../src/leave/admin/AdminForms.tsx';
import { PolicyNameAction, VersionEditor, VersionDecision, AccrualEditor, TypeRuleEditor } from '../src/leave/admin/PolicyActions.tsx';
import { LeaveTypeEditor, DisableLeaveType } from '../src/leave/admin/TypeActions.tsx';
import { AssignmentEditor, ManualGrantEditor, AdjustmentEditor } from '../src/leave/admin/EmployeeActions.tsx';
import { AdminRequestView, RequestManagement } from '../src/leave/admin/RequestManagement.tsx';
import { kst } from '../src/format/index.ts';

let client, renderer, respond, calls, latest;
const me = (role = 'USER', dutyName = '휴가관리자') => ({ empId: 1, dutyId: 99, dutyName, role, status: 'EMPLOYED', name: '테스트', deptId: 1 });
const version = { policyId: 2, policyVersionId: 3, versionNo: 1, isActive: false, baseType: 'HIRE_DATE', fiscalStart: null,
  unitMinutes: 30, carryOverAllowed: false, carryOverLimitMinutes: null, expiryRule: 'END_OF_VALIDITY', effectiveFrom: null, effectiveTo: null };
const versionInput = { baseType: version.baseType, fiscalStart: null, unitMinutes: 30, carryOverAllowed: false,
  carryOverLimitMinutes: null, expiryRule: 'END_OF_VALIDITY', effectiveFrom: null, effectiveTo: null };
const type = { leaveTypeId: 4, name: '연차', paid: true, balanceMode: 'ACCOUNT', balanceType: { balanceTypeId: 8, name: '연차', active: true }, requestUnit: 'MIXED', maxMinutes: null, isActive: true };
const grant = { balanceTypeId: 8, grantMode: 'INITIAL', grantMinutes: 480, validFrom: '2030-01-01T00:00:00Z', expiresOn: null, reason: '지급' };
const adjustment = { accountId: 10, grantId: 11, quantityDelta: -30, reason: '조정' };
const grantReceipt = { accountId: 10, grantId: 11, ledgerEntryId: 20, employeeId: 9, balanceTypeId: 8,
  policyVersionId: 3, grantMode: 'INITIAL', grantMinutes: 480, remainingMinutes: 480, asOf: '2030-01-01T00:00:00Z' };
const adjustmentReceipt = { ...adjustment, entryId: 20, employeeId: 9, eventType: 'ADJUST', reservedDelta: 0, actorEmpId: 1, createdAt: '2030-01-01T00:00:00Z' };
const details = (status = 'APPROVED') => ({ requestId: 12, employeeId: 9, policyVersionId: 3, leaveTypeName: '연차', status,
  totalMinutes: 480, totalDeductMinutes: 480, approvalDocId: 88, dates: [], reason: '개인 사유', createdAt: '2030-01-01T00:00:00Z' });

beforeEach(() => {
  client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity, gcTime: Infinity }, mutations: { gcTime: Infinity } } });
  calls = [];
  respond = () => [];
  sessionStorage.clear();
  useSession.setState({ me: me(), status: 'authenticated' });
  http.defaults.adapter = async (config) => {
    const call = { method: config.method, url: config.url, body: config.data ? JSON.parse(config.data) : undefined, key: config.headers.get('Idempotency-Key') };
    calls.push(call);
    const result = await respond(call);
    if (result?.errorStatus) {
      const response = { status: result.errorStatus, statusText: 'error', headers: {}, config,
        data: { success: false, error: { code: result.code ?? 'COMMON_001', message: result.message ?? '서버 검증 오류' }, data: null } };
      throw new AxiosError('rejected', 'ERR_BAD_RESPONSE', config, null, response);
    }
    return { status: 200, statusText: 'OK', headers: {}, config, data: { success: true, error: null, data: result } };
  };
});
afterEach(async () => { await act(async () => renderer?.unmount()); renderer = null; client.clear(); useSession.getState().clear(); });
async function settle() { await act(async () => { await new Promise((resolve) => setTimeout(resolve, 30)); }); }
async function mount(component) {
  await act(async () => { renderer = TestRenderer.create(React.createElement(QueryClientProvider, { client },
    React.createElement(MemoryRouter, { initialEntries: ['/admin/leave'], future: { v7_startTransition: true, v7_relativeSplatPath: true } }, component))); });
  await settle();
}
function Hook({ hook }) { latest = hook(); return null; }
const texts = () => JSON.stringify(renderer.toJSON(), (key, value) => key === 'props' ? {} : value);
async function click(text) {
  const button = renderer.root.findAllByType('button').find((b) => b.children.filter((c) => typeof c === 'string').join('') === text);
  assert.ok(button, `button ${text}`);
  await act(async () => button.props.onClick());
  await settle();
}
async function finish(values) {
  await act(async () => { await renderer.root.findByType('form').props.onFinish(values); });
  await settle();
}

for (const [role, duty, allowed] of [['USER', '휴가관리자', true], ['ADMIN', '휴가관리자', true], ['ADMIN', '팀장', false], ['USER', '팀원', false]]) {
  test(`권한: ${role} + ${duty}`, async () => {
    useSession.setState({ me: me(role, duty) });
    respond = (call) => call.url.endsWith('/me') ? me(role, duty) : [type];
    await mount(React.createElement(Routes, null,
      React.createElement(Route, { path: '/admin/leave', element: React.createElement(RequireLeaveAdministrator, null, React.createElement('secret', null, '허용')) }),
      React.createElement(Route, { path: '/', element: React.createElement('denied', null, '거부') })));
    assert.equal(renderer.root.findAllByType('secret').length, allowed ? 1 : 0);
    assert.equal(calls.some((c) => c.url.endsWith('/admin/types')), allowed);
  });
}
test('캐시된 허용도 최신 직원·서버 검증이 끝나기 전 노출하지 않는다', async () => {
  client.setQueryData(['leaveAdministrator', 1, 99, 'EMPLOYED'], true);
  let release;
  respond = () => new Promise((resolve) => { release = resolve; });
  await mount(React.createElement(RequireLeaveAdministrator, null, React.createElement('secret')));
  assert.equal(renderer.root.findAllByType('secret').length, 0);
  await act(async () => release(me('USER', '팀원')));
  await settle();
  assert.equal(renderer.root.findAllByType('secret').length, 0);
});
for (const status of [403, 503]) {
  test(`서버 권한 검증 ${status}에서 안전하게 차단`, async () => {
    respond = (call) => call.url.endsWith('/me') ? me() : { errorStatus: status, code: status === 403 ? 'AUTH_009' : 'LEAVE_017' };
    await mount(React.createElement(RequireLeaveAdministrator, null, React.createElement('secret')));
    assert.equal(renderer.root.findAllByType('secret').length, 0);
    if (status === 503) assert.equal(renderer.root.findAllByType('full-page-error').length, 1);
  });
}
test('미인증·휴직 직원의 메뉴용 권한을 부여하지 않는다', async () => {
  useSession.setState({ me: null, status: 'anonymous' });
  await mount(React.createElement(Hook, { hook: useLeaveAdministrator }));
  assert.equal(latest.data, undefined);
  assert.equal(calls.length, 0);
});
test('최신 직원의 재직·직책 정보가 다르면 허용하지 않는다', async () => {
  respond = () => ({ ...me(), status: 'ON_LEAVE' });
  await mount(React.createElement(Hook, { hook: useLeaveAdministrator }));
  assert.equal(latest.data, false);
});
test('직원 정보 변경·로그아웃에서 이전 권한 결과를 재사용하지 않는다', async () => {
  respond = (call) => call.url.endsWith('/me') ? (useSession.getState().me ?? me()) : [type];
  await mount(React.createElement(Hook, { hook: useLeaveAdministrator }));
  assert.equal(latest.data, true);
  await act(async () => useSession.setState({ me: { ...me('USER', '팀원'), dutyId: 100 } }));
  await settle(); assert.equal(latest.data, false);
  await act(async () => { client.clear(); useSession.getState().clear(); });
  assert.notEqual(latest.data, true);
});
test('다른 관리자 Guard의 Role 정책과 메뉴를 유지한다', async () => {
  assert.ok(adminNav.some((n) => n.to === '/admin/hr'));
  assert.equal(adminNav.some((n) => n.to === '/admin/leave'), false);
  assert.equal(leaveAdminNav[0].to, '/admin/leave');
  await mount(React.createElement(Routes, null,
    React.createElement(Route, { path: '/admin/leave', element: React.createElement(RequireAdmin, null, React.createElement('system-admin')) }),
    React.createElement(Route, { path: '/', element: React.createElement('denied') })));
  assert.equal(renderer.root.findAllByType('system-admin').length, 0);
});

const contractCases = [
  ['그룹 생성', () => leaveAdminApi.createPolicy({ name: ' 정책 ' }), 'post', '/policies', { name: ' 정책 ' }],
  ['그룹 수정', () => leaveAdminApi.updatePolicy(2, { name: '변경' }), 'patch', '/policies/2', { name: '변경' }],
  ['버전 생성', () => leaveAdminApi.createVersion(2, version), 'post', '/policies/2/versions', versionInput],
  ['버전 수정 null', () => leaveAdminApi.updateVersion(2, 3, { effectiveFrom: null }), 'patch', '/policies/2/versions/3', { effectiveFrom: null }],
  ['버전 삭제', () => leaveAdminApi.deleteVersion(2, 3), 'delete', '/policies/2/versions/3', undefined],
  ['활성화', () => leaveAdminApi.activateVersion(2, 3), 'put', '/policies/2/active-version', { versionId: 3 }],
  ['발생 규칙 전체 삭제', () => leaveAdminApi.replaceAccrual(2, 3, []), 'put', '/policies/2/versions/3/accrual-rules', { rules: [] }],
  ['유형 규칙', () => leaveAdminApi.upsertTypeRule(2, 3, 4, { isRequestable: true, allowedUnits: 'FULL', minMinutes: null, maxMinutes: null }), 'put', '/policies/2/versions/3/type-rules/4', { isRequestable: true, allowedUnits: 'FULL', minMinutes: null, maxMinutes: null }],
  ['유형 생성', () => leaveAdminApi.createType({ name: '유급', isPaid: true }), 'post', '/types', { name: '유급', isPaid: true }],
  ['유형 비활성화', () => leaveAdminApi.updateType(4, { isActive: false }), 'patch', '/types/4', { isActive: false }],
  ['정책 배정', () => leaveAdminApi.assignPolicy(9, { policyId: 2, effectiveFrom: null, effectiveTo: null }), 'post', '/employees/9/policy-assignments', { policyId: 2, effectiveFrom: null, effectiveTo: null }],
  ['수동 지급', () => leaveAdminApi.grant(9, 'grant-key', grant), 'post', '/employees/9/grants', grant],
  ['잔액 조정', () => leaveAdminApi.adjust(9, 'adjust-key', adjustment), 'post', '/employees/9/adjustments', adjustment],
  ['관리자 취소', () => leaveAdminApi.cancel(12, '사유'), 'post', '/requests/12/cancel', { reason: '사유' }],
];
for (const [label, execute, method, path, body] of contractCases) {
  test(`실제 API 계약: ${label}`, async () => {
    if (label === '수동 지급') respond = () => grantReceipt;
    if (label === '잔액 조정') respond = () => adjustmentReceipt;
    if (label === '관리자 취소') respond = () => ({ requestId: 12, status: 'CANCELLED', adminCancelStatus: 'COMPLETED', adminCancelRequestId: 'server-uuid' });
    await execute();
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, method);
    assert.equal(calls[0].url, `/api/v1/leaves/admin${path}`);
    assert.deepEqual(calls[0].body, body);
    if (label === '수동 지급' || label === '잔액 조정') assert.ok(calls[0].key);
    if (label === '관리자 취소') assert.equal(calls[0].key, undefined);
  });
}
test('CUSTOM 발생 규칙은 실제 필드만 전송한다', async () => {
  await leaveAdminApi.replaceAccrual(2, 3, [{ ruleId: 1, policyVersionId: 3, minServiceMonths: 0, frequency: 'CUSTOM', grantMinutes: 60, isActive: true }]);
  assert.deepEqual(calls[0].body.rules[0], { ruleId: 1, minServiceMonths: 0, maxServiceMonths: null, frequency: 'CUSTOM', grantMinutes: 60, isActive: true });
});
test('Mutation은 관련 관리자 캐시만 갱신하고 실패 때 값을 보존한다', async () => {
  client.setQueryData(['leaveAdmin', 'policies'], [{ policyId: 2, name: '기존' }]);
  client.setQueryData(['leaveAdmin', 'emp', 9, 'ledger'], []);
  await mount(React.createElement(Hook, { hook: () => useLeaveAdminMutation(leaveAdminApi.createPolicy, [['leaveAdmin', 'policies']]) }));
  await act(async () => latest.mutateAsync({ name: '신규' }));
  assert.equal(client.getQueryState(['leaveAdmin', 'policies']).isInvalidated, true);
  assert.equal(client.getQueryState(['leaveAdmin', 'emp', 9, 'ledger']).isInvalidated, false);
});
test('일반 쓰기 중복 호출은 캐시 갱신 완료까지 한 HTTP 호출을 공유한다', async () => {
  let release;
  respond = () => new Promise((resolve) => { release = () => resolve({ policyId: 2, name: '정책' }); });
  await mount(React.createElement(Hook, { hook: () => useLeaveAdminMutation(leaveAdminApi.createPolicy, [['leaveAdmin', 'policies']]) }));
  let first, second;
  await act(async () => { first = latest.mutateAsync({ name: '정책' }); second = latest.mutateAsync({ name: '정책' }); });
  assert.equal(calls.length, 1);
  await act(async () => { release(); await Promise.all([first, second]); });
});
test('입력 오류 응답이면 기존 정책 캐시를 유지하고 실제 오류 코드를 제공한다', async () => {
  client.setQueryData(['leaveAdmin', 'policies'], [{ policyId: 2, name: '기존' }]);
  respond = () => ({ errorStatus: 400, code: 'COMMON_001' });
  await mount(React.createElement(Hook, { hook: () => useLeaveAdminMutation(leaveAdminApi.createPolicy, [['leaveAdmin', 'policies']]) }));
  await act(async () => { await assert.rejects(latest.mutateAsync({ name: '' }), (error) => error.code === 'COMMON_001'); });
  assert.equal(client.getQueryState(['leaveAdmin', 'policies']).isInvalidated, false);
  assert.equal(client.getQueryData(['leaveAdmin', 'policies'])[0].name, '기존');
});

for (const operation of ['grant', 'adjust']) {
  test(`${operation}: Timeout·재마운트 후 같은 키·원문 유지, 동시 클릭 차단`, async () => {
    let count = 0;
    const received = [];
    const action = async (key, body) => { received.push({ key, body }); if (++count === 1) throw new ApiError(0, 'NETWORK', '응답 불명', null); return { ok: true }; };
    const body = operation === 'grant' ? grant : adjustment;
    const hook = () => usePersistentAdminRequest(operation, 9, action, [['leaveAdmin', 'emp', 9]]);
    await mount(React.createElement(Hook, { hook }));
    await act(async () => { await Promise.all([latest.execute(body), latest.execute(body)]); });
    assert.equal(received.length, 1);
    const original = received[0].key;
    assert.equal(latest.intent.key, original);
    await act(async () => renderer.unmount()); renderer = null;
    await mount(React.createElement(Hook, { hook }));
    await act(async () => latest.execute({ ...body, reason: '변경하면 안 됨' }));
    assert.deepEqual(received[1], { key: original, body });
    assert.equal(latest.intent, null);
    assert.equal(sessionStorage.getItem(`nexa.leave.admin:1:${operation}:9`), null);
  });
}
test('저장된 요청 손상 시 새 키를 만들어 지급하지 않는다', async () => {
  sessionStorage.setItem('nexa.leave.admin:1:grant:9', '{broken');
  let count = 0;
  await mount(React.createElement(Hook, { hook: () => usePersistentAdminRequest('grant', 9, async () => { count++; }, []) }));
  await act(async () => latest.execute(grant));
  assert.equal(count, 0);
});
test('지급 결과가 2xx여도 식별자·수량 불일치이면 키를 지우지 않는다', async () => {
  respond = () => ({ ...grantReceipt, employeeId: 999 });
  await mount(React.createElement(Hook, { hook: () => usePersistentAdminRequest('grant', 9,
    (key, input) => leaveAdminApi.grant(9, key, input), []) }));
  await act(async () => latest.execute(grant));
  assert.ok(latest.intent); assert.ok(latest.error);
});
test('응답이 불명확한 일반 생성은 저장 버튼을 막고 재조회 안내를 표시한다', async () => {
  respond = () => ({ errorStatus: 503, code: 'COMMON_999' });
  await mount(React.createElement(PolicyNameAction));
  await click('정책 그룹 생성'); await finish({ name: '정책' });
  assert.equal(renderer.root.findAllByType('button').find((b) => b.props.type === 'primary').props.disabled, true);
  assert.ok(renderer.root.findAllByType('alert').some((a) => a.props.message?.includes('불명확')));
});

test('정책 그룹 생성 화면은 공백 원문과 실제 응답 ID를 보존한다', async () => {
  let saved;
  respond = () => ({ policyId: 2, name: ' 정책 ' });
  await mount(React.createElement(PolicyNameAction, { onCreated: (id) => { saved = id; } }));
  await click('정책 그룹 생성'); await finish({ name: ' 정책 ' });
  assert.equal(saved, 2); assert.equal(calls[0].body.name, ' 정책 ');
});
test('활성 버전 수정·삭제를 비활성화한다', async () => {
  await mount(React.createElement(React.Fragment, null, React.createElement(VersionEditor, { policyId: 2, version: { ...version, isActive: true } }),
    React.createElement(VersionDecision, { policyId: 2, version: { ...version, isActive: true }, onDeleted() {} })));
  assert.ok(renderer.root.findAllByType('button').every((b) => b.props.disabled));
});
test('버전 생성 화면은 nullable 기간과 HIRE_DATE 설정을 전송한다', async () => {
  respond = () => version;
  await mount(React.createElement(VersionEditor, { policyId: 2 }));
  await click('새 버전 생성'); await finish({ ...version, effectiveFrom: null, effectiveTo: null });
  assert.equal(calls[0].body.effectiveFrom, null); assert.equal(calls[0].body.fiscalStart, null);
});
test('버전 활성화는 확인 모달 이후에만 요청한다', async () => {
  await mount(React.createElement(VersionDecision, { policyId: 2, version, onDeleted() {} }));
  await click('활성 버전 지정'); assert.equal(calls.length, 0);
  await click('활성화'); assert.deepEqual(calls[0].body, { versionId: 3 });
});
test('사용 이력으로 버전 삭제가 거부되면 모달과 서버 오류를 유지한다', async () => {
  respond = () => ({ errorStatus: 409, code: 'LEAVE_006' });
  await mount(React.createElement(VersionDecision, { policyId: 2, version, onDeleted() { throw new Error('삭제되면 안 됨'); } }));
  await click('버전 삭제'); await click('삭제');
  assert.equal(renderer.root.findByType('error-panel').props.error.code, 'LEAVE_006');
});
test('발생 규칙 수정은 전체 목록과 CUSTOM을 보존한다', async () => {
  await mount(React.createElement(AccrualEditor, { policyId: 2, versionId: 3, rules: [], disabled: false }));
  await click('발생 규칙 수정'); await finish({ rules: [{ minServiceMonths: 0, maxServiceMonths: 12, frequency: 'CUSTOM', grantMinutes: 60, isActive: true }] });
  assert.equal(calls[0].body.rules[0].frequency, 'CUSTOM');
});
test('유형별 규칙 수정은 URL에 유형 ID, Body에 설정만 보낸다', async () => {
  await mount(React.createElement(TypeRuleEditor, { policyId: 2, versionId: 3, types: [type], rules: [], disabled: false }));
  await click('신청 규칙 등록·수정'); await finish({ leaveTypeId: 4, isRequestable: true, allowedUnits: 'HALF', minMinutes: null, maxMinutes: 480 });
  assert.ok(calls[0].url.endsWith('/type-rules/4')); assert.equal(calls[0].body.leaveTypeId, undefined);
});
test('유형 수정 기본 모드는 이름·활성만 PATCH한다', async () => {
  await mount(React.createElement(LeaveTypeEditor, { type, types: [type] }));
  await click('수정'); await finish({ name: '변경', isActive: false, isPaid: true, balanceMode: 'ACCOUNT' });
  assert.deepEqual(calls[0].body, { name: '변경', isActive: false });
});
test('비활성화는 DELETE 대신 확인 후 isActive=false PATCH한다', async () => {
  await mount(React.createElement(DisableLeaveType, { type }));
  await click('비활성화');
  await act(async () => renderer.root.findAllByType('button').find((b) => b.props.type === 'primary').props.onClick());
  await settle(); assert.equal(calls[0].method, 'patch'); assert.deepEqual(calls[0].body, { isActive: false });
});
test('직원 배정은 버전 ID 없이 그룹과 기간을 보낸다', async () => {
  client.setQueryData(['leaveAdmin', 'policies'], [{ policyId: 2, name: '정책' }]);
  await mount(React.createElement(AssignmentEditor, { employeeId: 9 }));
  await click('정책 배정'); await finish({ policyId: 2, effectiveFrom: null, effectiveTo: null });
  assert.deepEqual(calls.find((c) => c.method === 'post').body, { policyId: 2, effectiveFrom: null, effectiveTo: null });
});
test('수동 지급 화면은 정확한 분·기간·키를 보낸다', async () => {
  respond = () => grantReceipt;
  await mount(React.createElement(ManualGrantEditor, { employeeId: 9, types: [type], onClose() {} }));
  await finish({ ...grant, validFrom: kst(grant.validFrom), expiresOn: null });
  assert.deepEqual(calls[0].body, { ...grant, validFrom: kst(grant.validFrom).toISOString() }); assert.ok(calls[0].key);
});
test('증감 조정 화면은 기존 계정·발생분과 음수 분 수량을 사용한다', async () => {
  respond = () => adjustmentReceipt;
  await mount(React.createElement(AdjustmentEditor, { employeeId: 9, grants: [], balances: [], onClose() {} }));
  await finish(adjustment); assert.deepEqual(calls[0].body, adjustment); assert.ok(calls[0].key);
});
test('조정 대상 선택에서 미래·만료 발생분을 제외한다', async () => {
  const base = { grantId: 11, accountId: 10, balanceTypeName: '연차', originalMinutes: 480, validFrom: '2000-01-01T00:00:00Z', expiresOn: null };
  await mount(React.createElement(AdjustmentEditor, { employeeId: 9, balances: [], onClose() {}, grants: [base,
    { ...base, grantId: 12, validFrom: '2099-01-01T00:00:00Z' }, { ...base, grantId: 13, expiresOn: '2001-01-01T00:00:00Z' }] }));
  assert.deepEqual(renderer.root.findByType('select').props.options.map((o) => o.value), [11]);
});

for (const status of ['SUBMITTED', 'SUBMITTING', 'REJECTED', 'CANCELLED']) {
  test(`신청 ${status}는 관리자 취소 버튼 비활성`, async () => {
    respond = () => details(status);
    await mount(React.createElement(AdminRequestView, { id: 12 }));
    assert.equal(renderer.root.findAllByType('button').find((b) => b.children.includes('승인 휴가 관리자 취소')).props.disabled, true);
  });
}
test('취소 UNKNOWN은 승인 유지·같은 사유 재확인, 확정 완료 후 상세·원장 갱신', async () => {
  let cancelled = false;
  respond = (call) => call.method === 'post' ? { requestId: 12, status: cancelled ? 'CANCELLED' : 'APPROVED',
    adminCancelStatus: cancelled ? 'COMPLETED' : 'UNKNOWN', adminCancelRequestId: 'same-server-uuid', message: '처리 결과' } : details(cancelled ? 'CANCELLED' : 'APPROVED');
  client.setQueryData(['leaveAdmin', 'emp', 9, 'ledger'], []);
  await mount(React.createElement(AdminRequestView, { id: 12 }));
  await click('승인 휴가 관리자 취소'); await finish({ reason: '취소 사유' });
  assert.ok(texts().includes('UNKNOWN')); assert.equal(renderer.root.findByType('form').props.disabled, true);
  cancelled = true;
  await click('같은 취소 요청 다시 확인');
  const posts = calls.filter((c) => c.method === 'post');
  assert.equal(posts.length, 2); assert.deepEqual(posts[0].body, posts[1].body);
  assert.equal(calls.some((c) => c.url.includes('/documents')), false);
  assert.equal(client.getQueryState(['leaveAdmin', 'emp', 9, 'ledger']).isInvalidated, true);
});
test('취소 503은 기능 비활성으로 안내하고 성공 상태로 변경하지 않는다', async () => {
  respond = (call) => call.method === 'post' ? { errorStatus: 503, code: 'LEAVE_025' } : details();
  await mount(React.createElement(AdminRequestView, { id: 12 }));
  await click('승인 휴가 관리자 취소'); await finish({ reason: '취소' });
  assert.equal(renderer.root.findByType('error-panel').props.error.code, 'LEAVE_025');
  assert.ok(renderer.root.findAllByType('alert').some((a) => a.props.message?.includes('비활성화')));
});
test('취소 전송 오류도 기존 사유를 잠그고 새 요청으로 바꾸지 않는다', async () => {
  respond = (call) => call.method === 'post' ? { errorStatus: 504, code: 'HTTP_504' } : details();
  await mount(React.createElement(AdminRequestView, { id: 12 }));
  await click('승인 휴가 관리자 취소'); await finish({ reason: '원문 사유' });
  assert.equal(renderer.root.findByType('form').props.disabled, true);
  await click('같은 취소 요청 다시 확인');
  assert.deepEqual(calls.filter((c) => c.method === 'post').map((c) => c.body), [{ reason: '원문 사유' }, { reason: '원문 사유' }]);
});
test('신청 목록은 명시적으로 전체 조회하며 미지원 필터를 보내지 않는다', async () => {
  respond = (call) => call.url.endsWith('/employees') ? { employees: [] } : [];
  await mount(React.createElement(RequestManagement));
  assert.equal(calls.some((c) => c.url.endsWith('/admin/requests')), false);
  await click('전체 신청 불러오기');
  assert.equal(calls.filter((c) => c.url.endsWith('/admin/requests')).length, 1);
  assert.ok(calls.every((c) => !c.url.includes('?')));
});
test('직원·그룹 전환 중 이전 대상의 데이터를 placeholder로 노출하지 않는다', async () => {
  client.setQueryData(['leaveAdmin', 'emp', 1, 'balances'], [{ accountId: 1 }]);
  client.setQueryData(['leaveAdmin', 'versions', 1], [{ policyVersionId: 1 }]);
  respond = () => new Promise(() => {});
  await mount(React.createElement(Hook, { hook: () => ({ balance: useEmployeeBalances(2), version: usePolicyVersions(2) }) }));
  assert.equal(latest.balance.data, undefined); assert.equal(latest.version.data, undefined);
});

test('정책 그룹 편집 전 상세 API에서 현재 이름을 조회한다', async () => {
  respond = () => ({ policyId: 2, name: '현재 정책 이름' });
  await mount(React.createElement(Hook, { hook: () => useLeavePolicy(2) }));
  assert.deepEqual(latest.data, { policyId: 2, name: '현재 정책 이름' });
  assert.equal(calls[0].url, '/api/v1/leaves/admin/policies/2');
});

test('정책 그룹 상세 404에서 다른 그룹의 캐시로 대체하지 않는다', async () => {
  client.setQueryData(['leaveAdmin', 'policy', 1], { policyId: 1, name: '다른 그룹' });
  respond = () => ({ errorStatus: 404, code: 'LEAVE_004' });
  await mount(React.createElement(Hook, { hook: () => useLeavePolicy(2) }));
  assert.equal(latest.data, undefined);
  assert.equal(latest.error.code, 'LEAVE_004');
});
