import { useState } from 'react';
import { Alert, Button, DatePicker, Form, Input, InputNumber, Select } from 'antd';
import type { Dayjs } from 'dayjs';
import { leaveAdminApi, useLeaveAdminMutation, useLeavePolicies, usePolicyVersions, type AdminGrant,
  type AdminLeaveType, type AdjustmentInput, type GrantInput, type AssignmentInput } from '@/api/leaveAdmin';
import type { LeaveBalance } from '@/api/leave';
import { kst, nowKst, formatLeaveAmount } from '@/format';
import { useNotify } from '@/ui/useNotify';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { ActionModal, employeeKeys, positiveId, reasonRules, PendingIntentNotice, usePersistentAdminRequest } from './AdminForms';

export function AssignmentEditor({ employeeId }: { employeeId: number }) {
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm<{ policyId: number; effectiveFrom: Dayjs | null; effectiveTo: Dayjs | null }>();
  const policies = useLeavePolicies();
  const selected = Form.useWatch('policyId', form);
  const versions = usePolicyVersions(open && selected ? selected : null);
  const notify = useNotify();
  const mutation = useLeaveAdminMutation((input: AssignmentInput) => leaveAdminApi.assignPolicy(employeeId, input), [...employeeKeys(employeeId), ['leave', 'policy'], ['leave', 'types']]);
  return <><Button onClick={() => setOpen(true)}>정책 배정</Button>
    {open && <ActionModal title={`직원 #${employeeId}에게 정책 그룹을 배정합니다`} pending={mutation.isPending} error={mutation.error}
      onClose={() => setOpen(false)} onSubmit={() => { if (versions.data?.some((v) => v.isActive)) form.submit(); }}>
      <Alert type="warning" message="정책 그룹을 배정하며 버전을 고정 배정하지 않습니다. 이후 그룹의 활성 버전을 사용합니다. 정책 배정만으로 휴가는 지급되지 않습니다."
        description="다른 그룹으로 미래·현재 배정을 변경하면 서버 조건에 따라 기존 무기한 배정이 종료될 수 있습니다. 중복·소급 충돌은 서버가 거부합니다." />
      {policies.isError && <ErrorPanel error={policies.error} onRetry={() => policies.refetch()} />}
      <Form form={form} clearOnDestroy disabled={mutation.isPending} layout="vertical" initialValues={{ effectiveFrom: null, effectiveTo: null }} onFinish={(values) => mutation.mutate({
        policyId: values.policyId, effectiveFrom: values.effectiveFrom?.toISOString() ?? null, effectiveTo: values.effectiveTo?.toISOString() ?? null,
      }, { onSuccess: () => { notify.success('정책 그룹을 배정했습니다', '휴가 지급은 별도로 수행하세요'); setOpen(false); } })}>
        <Form.Item name="policyId" label="정책 그룹" rules={positiveId}><Select loading={policies.isPending} options={(policies.data ?? []).map((p) => ({ value: p.policyId, label: p.name }))} /></Form.Item>
        <Form.Item name="effectiveFrom" label="적용 시작 (비우면 서버 현재 시각)"><DatePicker showTime /></Form.Item>
        <Form.Item name="effectiveTo" label="적용 종료 (비우면 무기한)" dependencies={['effectiveFrom']} rules={[({ getFieldValue }) => ({ validator: (_, value) => !value || value.isAfter(getFieldValue('effectiveFrom') ?? nowKst())
          ? Promise.resolve() : Promise.reject(new Error('종료는 시작보다 늦어야 합니다')) })]}><DatePicker showTime /></Form.Item>
      </Form>
      {selected && versions.isPending && <Alert type="info" message="활성 버전을 확인하고 있습니다" />}
      {versions.isError && <ErrorPanel error={versions.error} onRetry={() => versions.refetch()} />}
      {selected && versions.isSuccess && !versions.data.some((v) => v.isActive) && <Alert type="error" message="활성 버전이 없는 그룹은 배정할 수 없습니다" />}
    </ActionModal>}
  </>;
}

type GrantForm = Omit<GrantInput, 'validFrom' | 'expiresOn'> & { validFrom: Dayjs; expiresOn: Dayjs | null };
export function ManualGrantEditor({ employeeId, types, onClose }: { employeeId: number; types: AdminLeaveType[]; onClose: () => void }) {
  const operation = usePersistentAdminRequest<GrantInput, Awaited<ReturnType<typeof leaveAdminApi.grant>>>(
    'grant', employeeId, (key, body) => leaveAdminApi.grant(employeeId, key, body), employeeKeys(employeeId));
  const [form] = Form.useForm<GrantForm>();
  const minutes = Form.useWatch('grantMinutes', form);
  const notify = useNotify();
  const balances = [...new Map(types.flatMap((t) => t.balanceType?.active ? [[t.balanceType.balanceTypeId, t.balanceType] as const] : [])).values()];
  const initial = operation.intent?.body;
  const save = async (values: GrantForm) => {
    const result = await operation.execute({ balanceTypeId: values.balanceTypeId, grantMode: values.grantMode,
      grantMinutes: values.grantMinutes, validFrom: values.validFrom.toISOString(), expiresOn: values.expiresOn?.toISOString() ?? null, reason: values.reason });
    if (result) { notify.success('수동 휴가를 지급했습니다', `발생분 #${result.grantId} · 원장 #${result.ledgerEntryId}`); onClose(); }
  };
  return <ActionModal title={`직원 #${employeeId}에게 휴가를 수동 지급합니다`} allowUnknownRetry pending={operation.pending} error={operation.error} onClose={onClose}
    label={operation.intent ? '같은 지급 요청 다시 확인' : '지급 확인'} onSubmit={() => operation.intent ? void operation.execute(operation.intent.body).then((result) => {
      if (result) { notify.success('기존 지급 결과를 확인했습니다', `발생분 #${result.grantId}`); onClose(); }
    }) : form.submit()}>
    <PendingIntentNotice locked={operation.intent != null} />
    <Alert type="info" message="현재 활성 정책 배정과 유효한 잔액 유형이 필요합니다. INITIAL은 지급 이력이 없는 경우, ADDITIONAL은 이력이 있는 경우에만 사용합니다. 조정과 달리 새 발생분을 생성합니다." />
    <Form form={form} layout="vertical" disabled={operation.pending || operation.intent != null} initialValues={initial ? { ...initial,
      validFrom: kst(initial.validFrom), expiresOn: initial.expiresOn ? kst(initial.expiresOn) : null }
      : { grantMode: 'INITIAL', validFrom: nowKst(), expiresOn: null }} onFinish={save}>
      <Form.Item label="기존 연결된 잔액 유형"><Select allowClear options={balances.map((b) => ({ value: b.balanceTypeId, label: `${b.name} (#${b.balanceTypeId})` }))} onChange={(id) => form.setFieldValue('balanceTypeId', id)} /></Form.Item>
      <Form.Item name="balanceTypeId" label="잔액 유형 ID" rules={positiveId}><InputNumber min={1} /></Form.Item>
      <Form.Item name="grantMode" label="지급 구분" rules={[{ required: true }]}><Select options={[{ value: 'INITIAL', label: '최초 지급' }, { value: 'ADDITIONAL', label: '추가 지급' }]} /></Form.Item>
      <Form.Item name="grantMinutes" label="지급 수량 (분, 1일=480분)" rules={[{ required: true, type: 'integer', min: 1, max: 2147483647 }]}><InputNumber min={1} max={2147483647} /></Form.Item>
      {minutes > 0 && <p>{formatLeaveAmount(minutes)}</p>}
      <Form.Item name="validFrom" label="유효 시작 (서울 시각)" rules={[{ required: true }]}><DatePicker showTime allowClear={false} /></Form.Item>
      <Form.Item name="expiresOn" label="만료 (선택, 해당 시각부터 사용 불가)" dependencies={['validFrom']} rules={[({ getFieldValue }) => ({ validator: (_, value) => !value || (value.isAfter(getFieldValue('validFrom')) && value.isAfter(nowKst()))
        ? Promise.resolve() : Promise.reject(new Error('만료는 현재와 유효 시작보다 늦어야 합니다')) })]}><DatePicker showTime /></Form.Item>
      <Form.Item name="reason" label="지급 사유" rules={reasonRules}><Input.TextArea maxLength={500} showCount /></Form.Item>
    </Form>
    <Alert type="info" message="잔액 유형 전체 조회 API는 없어 기존 연결 목록 또는 실제 ID를 사용합니다. 지급 수량·시작·만료·사유를 확인한 뒤 지급 확인을 누르세요." />
  </ActionModal>;
}

export function AdjustmentEditor({ employeeId, grants, balances, onClose }: {
  employeeId: number; grants: AdminGrant[]; balances: LeaveBalance[]; onClose: () => void;
}) {
  const operation = usePersistentAdminRequest<AdjustmentInput, Awaited<ReturnType<typeof leaveAdminApi.adjust>>>(
    'adjust', employeeId, (key, body) => leaveAdminApi.adjust(employeeId, key, body), employeeKeys(employeeId));
  const [form] = Form.useForm<AdjustmentInput>();
  const grantId = Form.useWatch('grantId', form);
  const delta = Form.useWatch('quantityDelta', form);
  const grant = grants.find((g) => g.grantId === grantId);
  const account = balances.find((b) => b.accountId === grant?.accountId);
  const notify = useNotify();
  const eligible = grants.filter((g) => !kst(g.validFrom).isAfter(nowKst()) && (!g.expiresOn || kst(g.expiresOn).isAfter(nowKst())));
  const finish = async (input: AdjustmentInput) => {
    const result = await operation.execute(input);
    if (result) { notify.success('잔액을 조정했습니다', `원장 #${result.entryId} · ${formatLeaveAmount(result.quantityDelta)}`); onClose(); }
  };
  return <ActionModal title={`직원 #${employeeId}의 기존 발생분을 조정합니다`} allowUnknownRetry pending={operation.pending} error={operation.error} onClose={onClose}
    label={operation.intent ? '같은 조정 요청 다시 확인' : '조정 확인'} onSubmit={() => operation.intent ? void finish(operation.intent.body) : form.submit()}>
    <PendingIntentNotice locked={operation.intent != null} />
    <Alert type="warning" message="지급 이력이 있는 현재 유효 발생분만 조정합니다. 음수는 차감이며 예약량을 침범할 수 없습니다. 계정 전체 잔액만으로 발생분의 조정 가능량을 단정하지 않습니다." />
    <Form form={form} layout="vertical" disabled={operation.pending || operation.intent != null} initialValues={operation.intent?.body} onFinish={finish}>
      <Form.Item name="grantId" label="조정할 발생분" rules={positiveId}><Select options={eligible.map((g) => ({ value: g.grantId,
        label: `${g.balanceTypeName} · 발생분 #${g.grantId} · ${formatLeaveAmount(g.originalMinutes)} (최초 지급량)` }))}
        onChange={(id) => form.setFieldValue('accountId', grants.find((g) => g.grantId === id)?.accountId)} /></Form.Item>
      <Form.Item name="accountId" label="해당 계정 ID" rules={positiveId}><InputNumber readOnly min={1} /></Form.Item>
      <Form.Item name="quantityDelta" label="증감 수량 (분, 증가 + / 감소 -)" rules={[{ required: true, type: 'integer', min: -2147483648, max: 2147483647 }, { validator: (_, value) => value !== 0 ? Promise.resolve() : Promise.reject(new Error('0분 조정은 불가합니다')) }]}><InputNumber /></Form.Item>
      <Form.Item name="reason" label="조정 사유" rules={reasonRules}><Input.TextArea maxLength={500} showCount /></Form.Item>
    </Form>
    {account && <Alert type="info" message={`조회한 계정 가용량: ${formatLeaveAmount(account.remainingMinutes)}`}
      description={Number.isInteger(delta) ? `계정 기준 예상 가용량: ${formatLeaveAmount(account.remainingMinutes + delta)}. 동시 처리와 발생분별 예약 검증에 따라 서버 결과가 달라질 수 있습니다.` : undefined} />}
    {eligible.length === 0 && <Alert type="info" message="현재 유효한 발생분이 없습니다. 최초 지급 대신 ADJUST를 사용할 수 없습니다." />}
  </ActionModal>;
}
