import { test, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import TestRenderer, { act } from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { http } from '../src/api/client.ts';
import { leaveKeys, useLeaveRequest, useMyLeaveRequests, useLeavePreview } from '../src/api/leave.ts';
import { useDocument } from '../src/api/approval.ts';
import { LeaveApply } from '../src/leave/Apply.tsx';
import { LeaveRequestList } from '../src/leave/Requests.tsx';
import { formatLeaveAmount, nowKst } from '../src/format/index.ts';

let client;
let renderer;
let calls;
let respond;
let latest;
beforeEach(() => {
  client = new QueryClient({ defaultOptions: {
    queries: { retry: false, staleTime: Infinity, gcTime: Infinity }, mutations: { retry: false, gcTime: Infinity },
  } });
  calls = [];
  respond = () => [];
  document.hidden = false;
  http.defaults.adapter = async (config) => {
    calls.push({ url: config.url, method: config.method, body: config.data, headers: config.headers });
    const data = await respond(config);
    return { data: { success: true, error: null, data }, status: 200, statusText: 'OK', headers: {}, config };
  };
});
afterEach(async () => {
  await act(async () => renderer?.unmount());
  renderer = undefined;
  client.clear();
});
async function mount(component) {
  await act(async () => {
    renderer = TestRenderer.create(React.createElement(QueryClientProvider, { client },
      React.createElement(MemoryRouter, null, component)));
  });
  await settle();
}
async function settle() {
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 25)); });
}
function hookTree(hook) {
  return React.createElement(QueryClientProvider, { client }, React.createElement(MemoryRouter, null, React.createElement(Hook, { hook })));
}
function Hook({ hook }) { latest = hook(); return null; }
const snapshot = () => JSON.stringify(renderer.toJSON(), (key, value) => key === 'props'
  ? Object.fromEntries(Object.entries(value).filter(([, v]) => v == null || ['string', 'number', 'boolean'].includes(typeof v))) : value);
const previewInput = (day = '2030-01-07') => ({ leaveTypeId: 1, reason: null, dates: [{ leaveDate: day, portion: 'FULL' }] });
const preview = (input, extra = {}) => ({ requestable: true, validationStatus: 'REQUESTABLE', holidayVerification: 'VERIFIED',
  balanceDeductionMinutes: 480, totalRequestedMinutes: 480, remainingMinutes: 960, balanceRequired: true,
  dates: input.dates.map((d) => ({ ...d, balanceDeductionMinutes: 480, requestedMinutes: 480 })), reasons: [], ...extra });
const detail = (status) => ({ requestId: 7, status, approvalDocId: 10, dates: [] });

for (const [minutes, expected] of [[0, '0분'], [30, '30분 (약 0.1일)'], [-30, '-30분 (약 -0.1일)'],
  [60, '1시간 (약 0.1일)'], [240, '4시간 (0.5일)'], [480, '8시간 (1.0일)'], [5460, '91시간 (약 11.4일)']]) {
  test(`정확한 분 수량 표시: ${minutes}`, () => assert.equal(formatLeaveAmount(minutes), expected));
}

test('선택 해제·새 입력 대기 중 이전 Preview를 반환하지 않는다', async () => {
  let input = previewInput();
  respond = () => preview(input);
  await mount(React.createElement(Hook, { hook: () => useLeavePreview(input) }));
  assert.equal(latest.data.balanceDeductionMinutes, 480);
  input = null;
  await act(async () => renderer.update(hookTree(() => useLeavePreview(input))));
  assert.equal(latest.data, undefined);
  const count = calls.length;
  await settle();
  assert.equal(calls.length, count);
});

test('늦은 이전 응답은 새 입력의 Preview를 덮어쓰지 않는다', async () => {
  const first = previewInput();
  const second = previewInput('2030-01-08');
  let release;
  respond = (config) => JSON.parse(config.data).dates[0].leaveDate === first.dates[0].leaveDate
    ? new Promise((resolve) => { release = () => resolve(preview(first)); }) : preview(second, { balanceDeductionMinutes: 240 });
  await mount(React.createElement(Hook, { hook: () => useLeavePreview(first) }));
  await act(async () => renderer.update(hookTree(() => useLeavePreview(second))));
  await settle();
  await act(async () => release());
  await settle();
  assert.equal(latest.data.balanceDeductionMinutes, 240);
  assert.equal(latest.data.dates[0].leaveDate, '2030-01-08');
});

for (const state of ['APPROVED', 'REJECTED', 'WITHDRAWN']) {
  test(`상세 ${state} 전환 시 잔액·원장·발생·목록 캐시 갱신`, async () => {
    for (const key of [leaveKeys.requests, leaveKeys.summary, leaveKeys.grants, leaveKeys.ledger]) client.setQueryData(key, []);
    client.setQueryData(leaveKeys.request(7), detail('SUBMITTED'));
    respond = () => detail(state);
    await mount(React.createElement(Hook, { hook: () => useLeaveRequest(7) }));
    await act(async () => latest.refetch());
    await settle();
    assert.equal(latest.data.status, state);
    for (const key of [leaveKeys.requests, leaveKeys.summary, leaveKeys.grants, leaveKeys.ledger]) {
      assert.equal(client.getQueryState(key).isInvalidated, true);
    }
  });
}

test('목록 상태 변경은 열린 상세 캐시도 무효화한다', async () => {
  client.setQueryData(leaveKeys.requests, [detail('SUBMITTED')]);
  client.setQueryData(leaveKeys.request(7), detail('SUBMITTED'));
  respond = () => [detail('APPROVED')];
  await mount(React.createElement(Hook, { hook: () => useMyLeaveRequests() }));
  await act(async () => latest.refetch());
  await settle();
  assert.equal(client.getQueryState(leaveKeys.request(7)).isInvalidated, true);
});

test('다른 창의 승인을 실제 폴링으로 감지하고 최종 상태에서 요청을 멈춘다', async () => {
  client.setQueryData(leaveKeys.requests, [detail('SUBMITTED')]);
  client.setQueryData(leaveKeys.request(7), detail('SUBMITTED'));
  respond = (config) => config.url.endsWith('/requests') ? [detail('APPROVED')] : detail('APPROVED');
  await mount(React.createElement(Hook, { hook: () => ({ list: useMyLeaveRequests(), detail: useLeaveRequest(7) }) }));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 5300)); });
  await settle();
  assert.equal(latest.detail.data.status, 'APPROVED');
  assert.equal(latest.list.data[0].status, 'APPROVED');
  const count = calls.length;
  assert.ok(count > 0 && count <= 4);
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 5300)); });
  assert.equal(calls.length, count);
});

test('숨긴 탭과 2분 한도에서 상세 폴링을 중단한다', async (t) => {
  client.setQueryData(leaveKeys.request(7), detail('SUBMITTED'));
  respond = () => detail('SUBMITTED');
  document.hidden = true;
  await mount(React.createElement(Hook, { hook: () => useLeaveRequest(7) }));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 5300)); });
  assert.equal(calls.length, 0);
  document.hidden = false;
  const originalNow = Date.now();
  t.mock.method(Date, 'now', () => originalNow + 121_000);
  await act(async () => renderer.update(hookTree(() => useLeaveRequest(7))));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 5300)); });
  assert.equal(calls.length, 0);
});

test('휴가에서 요청한 연결 문서만 진행 중 폴링을 사용한다', async () => {
  respond = () => ({ documentId: 10, status: 'INPROGRESS' });
  await mount(React.createElement(Hook, { hook: () => useDocument(10, true) }));
  const query = client.getQueryCache().getAll()[0];
  assert.equal(query.observers[0].options.refetchInterval(query), 5000);
  await act(async () => client.setQueryData(query.queryKey, { documentId: 10, status: 'APPROVED' }));
  await settle();
  assert.equal(query.observers[0].options.refetchInterval(query), false);
  await act(async () => client.setQueryData(query.queryKey, { documentId: 10, status: 'INPROGRESS' }));
  await act(async () => renderer.update(hookTree(() => useDocument(10))));
  assert.equal(query.observers[0].options.refetchInterval(query), false);
});

async function mountApply(result = {}) {
  client.setQueryData(leaveKeys.types, [{ leaveTypeId: 1, leaveTypeName: '연차', allowedUnits: 'MIXED' }]);
  client.setQueryData(leaveKeys.requests, []);
  respond = (config) => config.url.endsWith('/preview') ? preview(JSON.parse(config.data), result) : [];
  await mount(React.createElement(LeaveApply));
}
async function selectDate() {
  const date = nowKst().endOf('month').format('YYYY-MM-DD');
  const button = renderer.root.findAllByType('button').find((b) => b.props['aria-pressed'] === false && !b.props.disabled
    && b.props['aria-label']?.includes(`${nowKst().month() + 1}.${Number(date.slice(-2))}`));
  const candidate = button ?? renderer.root.findAllByType('button').filter((b) => b.props['aria-pressed'] === false && !b.props.disabled).at(-1);
  await act(async () => candidate.props.onClick());
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 350)); });
  await settle();
}

test('신청 화면 전체 선택 해제 시 합계 제거, 입력이 그대로면 Preview 재호출 없음', async () => {
  await mountApply();
  await selectDate();
  assert.ok(snapshot().includes('차감 합계'));
  const count = calls.filter((c) => c.url.endsWith('/preview')).length;
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 700)); });
  assert.equal(calls.filter((c) => c.url.endsWith('/preview')).length, count);
  await act(async () => renderer.root.findByType('textarea').props.onChange({ target: { value: '사유만 변경' } }));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 350)); });
  assert.equal(calls.filter((c) => c.url.endsWith('/preview')).length, count);
  await act(async () => renderer.root.findAllByType('button').find((b) => b.props['aria-pressed'] === true).props.onClick());
  assert.equal(snapshot().includes('차감 합계'), false);
});

for (const reason of ['WEEKEND', 'HOLIDAY', 'INSUFFICIENT_BALANCE']) {
  test(`신청 불가 ${reason}: 예상 잔여 숨김·신청 차단·날짜 사유 보존`, async () => {
    await mountApply({ requestable: false, validationStatus: 'NOT_REQUESTABLE', reasons: [{ code: reason, date: '2030-01-07', message: '검증 사유' }] });
    await selectDate();
    assert.equal(snapshot().includes('신청 후 잔여'), false);
    const submit = renderer.root.findAllByType('button').find((b) => b.props.type === 'primary');
    assert.equal(submit.props.disabled, true);
    if (reason === 'INSUFFICIENT_BALANCE') assert.ok(renderer.root.findAllByType('alert').some((a) => a.props.message === '검증 사유'));
  });
}

test('잔액 미차감 유형에 음수 0 표시가 없다', async () => {
  await mountApply({ balanceRequired: false, balanceDeductionMinutes: 0, dates: [{ leaveDate: nowKst().endOf('month').format('YYYY-MM-DD'), portion: 'FULL', balanceDeductionMinutes: 0 }] });
  await selectDate();
  assert.ok(snapshot().includes('차감 없음'));
  assert.equal(snapshot().includes('-0.0일'), false);
});

test('366개 선택 한도를 안내하고 367번째 선택은 추가하지 않는다', async () => {
  await mountApply();
  for (let month = 0; month < 15; month++) {
    if (renderer.root.findAllByType('alert').some((a) => a.props.message?.includes('최대 366개'))) break;
    const buttons = renderer.root.findAllByType('button').filter((b) => b.props['aria-pressed'] === false && !b.props.disabled);
    await act(async () => { for (const button of buttons) button.props.onClick(); });
    if (!renderer.root.findAllByType('alert').some((a) => a.props.message?.includes('최대 366개'))) {
      await act(async () => renderer.root.findAllByType('button').find((b) => b.props['aria-label'] === '다음 달').props.onClick());
    }
  }
  assert.ok(renderer.root.findAllByType('alert').some((a) => a.props.message?.includes('최대 366개')));
  const remaining = renderer.root.findAllByType('button').find((b) => b.props['aria-pressed'] === false && !b.props.disabled);
  if (remaining) await act(async () => remaining.props.onClick());
  assert.ok(renderer.root.findAllByType('card').some((c) => c.props.title === '선택한 날짜 366'));
});

test('처리 확인 중에는 편집 Preview를 중단하고 동일 키로 재확인한다', async () => {
  await mountApply();
  await selectDate();
  respond = (config) => config.method === 'post' && config.url.endsWith('/requests')
    ? { requestId: 7, status: 'SUBMITTING', approvalCreationStatus: 'UNKNOWN', approvalDocId: null, message: '결과 확인 필요' }
    : config.url.endsWith('/preview') ? preview(JSON.parse(config.data))
    : config.url.endsWith('/types') ? [{ leaveTypeId: 1, leaveTypeName: '연차', allowedUnits: 'MIXED' }] : [];
  await act(async () => renderer.root.findAllByType('button').find((b) => b.props.type === 'primary').props.onClick());
  await settle();
  const previewCount = calls.filter((c) => c.url.endsWith('/preview')).length;
  assert.equal(snapshot().includes('차감 합계'), false);
  assert.equal(renderer.root.findByType('textarea').props.disabled, true);
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 350)); });
  assert.equal(calls.filter((c) => c.url.endsWith('/preview')).length, previewCount);
  const retry = renderer.root.findAllByType('button').find((b) => b.children.includes('같은 요청으로 다시 확인'));
  await act(async () => retry.props.onClick());
  await settle();
  const submissions = calls.filter((c) => c.method === 'post' && c.url.endsWith('/requests'));
  assert.equal(submissions.length, 2);
  assert.equal(submissions[0].headers.get('Idempotency-Key'), submissions[1].headers.get('Idempotency-Key'));
  assert.equal(submissions[0].body, submissions[1].body);
});

for (const [noPolicy, canApply, description] of [[true, false, '정책이 배정되지'], [false, false, '신청할 수 있는 휴가 유형이 없습니다'], [false, true, '달력에서']]) {
  test(`빈 목록 정책 상태: noPolicy=${noPolicy}, canApply=${canApply}`, async () => {
    client.setQueryData(leaveKeys.requests, []);
    await mount(React.createElement(LeaveRequestList, { onOpen() {}, noPolicy, canApply }));
    assert.ok(renderer.root.findByType('empty-state').props.description.includes(description));
    assert.equal(!!renderer.root.findByType('empty-state').props.action, canApply);
  });
}
