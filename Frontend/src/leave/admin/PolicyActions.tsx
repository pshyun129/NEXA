import { useState } from 'react';
import { Alert, Button, Form, Input, InputNumber, Select, Switch, DatePicker, Space } from 'antd';
import type { Dayjs } from 'dayjs';
import { isApiError } from '@/api/ApiError';
import { leaveAdminApi, useLeaveAdminMutation, type LeavePolicyVersion, type LeaveAccrualRule,
  type VersionInput, type AccrualRuleInput, type AdminLeaveType, type LeaveTypeRule, type TypeRuleInput } from '@/api/leaveAdmin';
import { useNotify } from '@/ui/useNotify';
import { kst } from '@/format';
import { ActionModal, policyKeys, positiveId, requiredName } from './AdminForms';

export function PolicyNameAction({ policy, onCreated }: { policy?: { policyId: number; name: string }; onCreated?: (id: number) => void }) {
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm<{ name: string }>();
  const notify = useNotify();
  const mutation = useLeaveAdminMutation((input: { name: string }) => policy
    ? leaveAdminApi.updatePolicy(policy.policyId, input) : leaveAdminApi.createPolicy(input),
    [...policyKeys(), ...(policy ? [['leaveAdmin', 'policy', policy.policyId], ['leaveAdmin', 'emp'], ['leave', 'policy']] : [])]);
  return <><Button onClick={() => setOpen(true)}>{policy ? '그룹 이름 수정' : '정책 그룹 생성'}</Button>
    {open && <ActionModal title={policy ? '정책 그룹 이름을 수정합니다' : '정책 그룹을 생성합니다'} pending={mutation.isPending}
      error={mutation.error} onClose={() => setOpen(false)} onSubmit={() => form.submit()}>
      <Form form={form} clearOnDestroy disabled={mutation.isPending} layout="vertical" initialValues={{ name: policy?.name ?? '' }} onFinish={(input) => mutation.mutate(input, {
        onSuccess: (saved) => { notify.success('정책 그룹을 저장했습니다'); setOpen(false); onCreated?.(saved.policyId); },
      })}>
        <Form.Item name="name" label="정책 그룹 이름" rules={requiredName}><Input maxLength={100} /></Form.Item>
      </Form>
      <Alert type="info" message="그룹 생성은 버전 생성·활성화·직원 배정·휴가 지급과 별도입니다. 동일한 이름도 허용됩니다." />
    </ActionModal>}
  </>;
}

type VersionForm = Omit<VersionInput, 'effectiveFrom' | 'effectiveTo'> & { effectiveFrom: Dayjs | null; effectiveTo: Dayjs | null };
export function VersionEditor({ policyId, version, onSaved }: { policyId: number; version?: LeavePolicyVersion; onSaved?: (id: number) => void }) {
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm<VersionForm>();
  const baseType = Form.useWatch('baseType', form);
  const carry = Form.useWatch('carryOverAllowed', form);
  const notify = useNotify();
  const mutation = useLeaveAdminMutation((input: VersionInput) => version
    ? leaveAdminApi.updateVersion(policyId, version.policyVersionId, input) : leaveAdminApi.createVersion(policyId, input), policyKeys(policyId));
  const save = (values: VersionForm) => {
    const input: VersionInput = { ...values, fiscalStart: values.baseType === 'FISCAL_YEAR' ? values.fiscalStart : null,
      carryOverLimitMinutes: values.carryOverAllowed ? values.carryOverLimitMinutes ?? null : null,
      effectiveFrom: values.effectiveFrom?.toISOString() ?? null, effectiveTo: values.effectiveTo?.toISOString() ?? null };
    mutation.mutate(input, { onSuccess: (saved) => { notify.success('정책 버전을 저장했습니다'); setOpen(false); onSaved?.(saved.policyVersionId); } });
  };
  return <><Button disabled={version?.isActive} onClick={() => setOpen(true)}>{version ? '버전 수정' : '새 버전 생성'}</Button>
    {open && <ActionModal title={version ? `v${version.versionNo} 설정을 수정합니다` : '새 정책 버전을 생성합니다'}
      pending={mutation.isPending} error={mutation.error} onClose={() => setOpen(false)} onSubmit={() => form.submit()}>
      <Alert type="info" message="활성·과거 사용 버전은 변경할 수 없습니다. 시행 기간이 활성 버전을 자동 선택하지 않으며 새 버전에는 규칙을 별도로 등록해야 합니다." />
      <Form form={form} clearOnDestroy disabled={mutation.isPending} layout="vertical" initialValues={version ? { ...version,
        effectiveFrom: version.effectiveFrom ? kst(version.effectiveFrom) : null,
        effectiveTo: version.effectiveTo ? kst(version.effectiveTo) : null }
        : { baseType: 'HIRE_DATE', fiscalStart: null, unitMinutes: 30, carryOverAllowed: false,
          carryOverLimitMinutes: null, expiryRule: 'END_OF_VALIDITY', effectiveFrom: null, effectiveTo: null }} onFinish={save}>
        <Form.Item name="baseType" label="발생 기준" rules={[{ required: true }]}><Select options={[{ value: 'HIRE_DATE', label: '입사일' }, { value: 'FISCAL_YEAR', label: '회계연도' }]} /></Form.Item>
        <Form.Item name="fiscalStart" label="회계연도 시작일 (MM-DD)" rules={baseType === 'FISCAL_YEAR'
          ? [{ required: true }, { validator: (_, value) => {
            const parsed = /^\d{2}-\d{2}$/.test(value ?? '') && kst(`2001-${value}`).format('MM-DD') === value;
            return parsed ? Promise.resolve() : Promise.reject(new Error('매년 유효한 MM-DD를 입력하세요 (02-29 제외)'));
          } }] : []}><Input disabled={baseType !== 'FISCAL_YEAR'} maxLength={5} placeholder="01-01" /></Form.Item>
        <Form.Item name="unitMinutes" label="계산 단위 (분)" rules={positiveId}><InputNumber min={1} /></Form.Item>
        <Form.Item name="carryOverAllowed" label="이월 허용" valuePropName="checked"><Switch /></Form.Item>
        <Form.Item name="carryOverLimitMinutes" label="이월 상한 (분, 비우면 제한 없음)" rules={[{ type: 'integer', min: 1 }]}><InputNumber disabled={!carry} min={1} /></Form.Item>
        <Form.Item name="expiryRule" label="만료 규칙" rules={[{ required: true, whitespace: true }, { max: 50 }]}><Input maxLength={50} /></Form.Item>
        <Form.Item name="effectiveFrom" label="시행 시작 (선택, 서울 시각)"><DatePicker showTime /></Form.Item>
        <Form.Item name="effectiveTo" label="시행 종료 (선택, 서울 시각)" dependencies={['effectiveFrom']} rules={[({ getFieldValue }) => ({ validator: (_, value) =>
          !value || !getFieldValue('effectiveFrom') || value.isAfter(getFieldValue('effectiveFrom')) ? Promise.resolve() : Promise.reject(new Error('종료는 시작보다 늦어야 합니다')) })]}><DatePicker showTime /></Form.Item>
      </Form>
    </ActionModal>}
  </>;
}

export function VersionDecision({ policyId, version, onDeleted }: { policyId: number; version: LeavePolicyVersion; onDeleted: () => void }) {
  const [operation, setOperation] = useState<'activate' | 'delete' | null>(null);
  const notify = useNotify();
  const mutation = useLeaveAdminMutation((kind: 'activate' | 'delete') => kind === 'activate'
    ? leaveAdminApi.activateVersion(policyId, version.policyVersionId) : leaveAdminApi.deleteVersion(policyId, version.policyVersionId), policyKeys(policyId));
  return <Space><Button disabled={version.isActive} onClick={() => setOperation('activate')}>활성 버전 지정</Button>
    <Button danger disabled={version.isActive} onClick={() => setOperation('delete')}>버전 삭제</Button>
    {operation && <ActionModal title={operation === 'activate' ? `v${version.versionNo}을 활성화합니다` : `v${version.versionNo}을 삭제합니다`}
      label={operation === 'activate' ? '활성화' : '삭제'} danger={operation === 'delete'} pending={mutation.isPending} error={mutation.error}
      onClose={() => setOperation(null)} onSubmit={() => mutation.mutate(operation, { onSuccess: () => {
        notify.success(operation === 'activate' ? '활성 버전을 변경했습니다' : '버전을 삭제했습니다');
        if (operation === 'delete') onDeleted(); setOperation(null);
      } })}>
      <Alert type="warning" message={operation === 'activate'
        ? '그룹의 기존 활성 버전은 교체됩니다. 배정 직원의 이후 신청·발생에 적용되며 과거 신청·발생분은 변경하지 않습니다.'
        : '미사용·비활성 버전만 삭제할 수 있습니다. 이 버전의 발생 규칙과 신청 규칙도 함께 삭제됩니다.'} />
    </ActionModal>}
  </Space>;
}

export function AccrualEditor({ policyId, versionId, rules, disabled }: { policyId: number; versionId: number; rules: LeaveAccrualRule[]; disabled: boolean }) {
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm<{ rules: AccrualRuleInput[] }>();
  const notify = useNotify();
  const mutation = useLeaveAdminMutation((values: { rules: AccrualRuleInput[] }) => leaveAdminApi.replaceAccrual(policyId, versionId, values.rules), policyKeys(policyId));
  return <><Button disabled={disabled} onClick={() => setOpen(true)}>발생 규칙 수정</Button>
    {open && <ActionModal title="발생 규칙 전체를 교체합니다" pending={mutation.isPending} error={mutation.error} onClose={() => setOpen(false)} onSubmit={() => form.submit()}>
      <Alert type="warning" message="목록에서 제거한 기존 규칙은 삭제됩니다. 빈 목록 저장은 모든 발생 규칙을 제거합니다. 활성·과거 사용 버전은 서버가 변경을 거부합니다." />
      <Form form={form} clearOnDestroy disabled={mutation.isPending} layout="vertical" initialValues={{ rules }} onFinish={(values) => mutation.mutate(values, { onSuccess: () => { notify.success('발생 규칙을 저장했습니다'); setOpen(false); } })}>
        <Form.List name="rules">{(fields, { add, remove }) => <>
          {fields.map((field) => <div key={field.key} style={{ borderBottom: '1px solid var(--nx-divider)', marginBottom: 12 }}>
            <Form.Item name={[field.name, 'ruleId']} hidden><InputNumber /></Form.Item>
            <Form.Item name={[field.name, 'minServiceMonths']} label="최소 근속 개월" rules={[{ required: true, type: 'integer', min: 0 }]}><InputNumber min={0} /></Form.Item>
            <Form.Item name={[field.name, 'maxServiceMonths']} label="최대 근속 개월 (선택)" rules={[{ type: 'integer', min: 0 }, ({ getFieldValue }) => ({ validator: (_, value) => value == null || value >= getFieldValue(['rules', field.name, 'minServiceMonths'])
              ? Promise.resolve() : Promise.reject(new Error('최대 개월은 최소 개월 이상이어야 합니다')) })]}><InputNumber min={0} /></Form.Item>
            <Form.Item name={[field.name, 'frequency']} label="발생 주기" rules={[{ required: true }]}><Select options={['MONTHLY', 'YEARLY', 'ON_HIRE', 'CUSTOM'].map((value) => ({ value, label: value }))} /></Form.Item>
            <Form.Item name={[field.name, 'grantMinutes']} label="발생 시간 (분)" rules={positiveId}><InputNumber min={1} /></Form.Item>
            <Form.Item name={[field.name, 'isActive']} label="사용" valuePropName="checked"><Switch /></Form.Item>
            <Button danger onClick={() => remove(field.name)}>이 규칙 제거</Button>
          </div>)}
          <Button onClick={() => add({ minServiceMonths: 0, maxServiceMonths: null, frequency: 'YEARLY', grantMinutes: 480, isActive: true })}>규칙 추가</Button>
        </>}</Form.List>
      </Form>
      <Alert type="info" message="CUSTOM도 동일한 근속 구간·분 수량 설정만 저장합니다. 별도 실행 일정이나 자동 지급 작업을 생성하지 않습니다." />
    </ActionModal>}
  </>;
}

export function TypeRuleEditor({ policyId, versionId, types, rules, disabled }: {
  policyId: number; versionId: number; types: AdminLeaveType[]; rules: LeaveTypeRule[]; disabled: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm<TypeRuleInput & { leaveTypeId: number }>();
  const selectedId = Form.useWatch('leaveTypeId', form);
  const master = types.find((t) => t.leaveTypeId === selectedId);
  const units = master?.requestUnit === 'MIXED' ? ['FULL', 'HALF', 'HOUR', 'MIXED']
    : master?.requestUnit === 'HALF_DAY' ? ['HALF'] : master?.requestUnit === 'HOUR' ? ['HOUR'] : ['FULL'];
  const notify = useNotify();
  const mutation = useLeaveAdminMutation((values: TypeRuleInput & { leaveTypeId: number }) => {
    const { leaveTypeId, ...input } = values;
    return leaveAdminApi.upsertTypeRule(policyId, versionId, leaveTypeId, { ...input, minMinutes: input.minMinutes ?? null, maxMinutes: input.maxMinutes ?? null });
  }, policyKeys(policyId));
  return <><Button disabled={disabled || types.length === 0} onClick={() => setOpen(true)}>신청 규칙 등록·수정</Button>
    {open && <ActionModal title="휴가 유형별 신청 규칙을 저장합니다" pending={mutation.isPending} error={mutation.error} onClose={() => setOpen(false)} onSubmit={() => form.submit()}>
      <Form form={form} clearOnDestroy disabled={mutation.isPending} layout="vertical" initialValues={{ isRequestable: true, minMinutes: null, maxMinutes: null }} onFinish={(values) => mutation.mutate(values, { onSuccess: () => { notify.success('신청 규칙을 저장했습니다'); setOpen(false); } })}>
        <Form.Item name="leaveTypeId" label="휴가 유형" rules={positiveId}><Select options={types.map((t) => ({ value: t.leaveTypeId, label: t.name }))} onChange={(id) => {
          const rule = rules.find((r) => r.leaveTypeId === id); form.setFieldsValue(rule ? { ...rule } : { allowedUnits: undefined, isRequestable: true, minMinutes: null, maxMinutes: null });
        }} /></Form.Item>
        <Form.Item name="isRequestable" label="신청 허용" valuePropName="checked"><Switch /></Form.Item>
        <Form.Item name="allowedUnits" label="허용 단위" rules={[{ required: true }, { validator: (_, value) => units.includes(value) ? Promise.resolve() : Promise.reject(new Error('휴가 유형 마스터와 호환되지 않는 단위입니다')) }]}><Select options={units.map((value) => ({ value, label: value }))} /></Form.Item>
        <Form.Item name="minMinutes" label="최소 신청 시간 (분)" rules={[{ type: 'integer', min: 1, max: master?.maxMinutes ?? undefined }]}><InputNumber min={1} /></Form.Item>
        <Form.Item name="maxMinutes" label="최대 신청 시간 (분, 비워도 마스터 상한 유지)" dependencies={['minMinutes']} rules={[{ type: 'integer', min: 1, max: master?.maxMinutes ?? undefined }, ({ getFieldValue }) => ({ validator: (_, value) => value == null || getFieldValue('minMinutes') == null || value >= getFieldValue('minMinutes')
          ? Promise.resolve() : Promise.reject(new Error('최대 시간은 최소 시간 이상이어야 합니다')) })]}><InputNumber min={1} /></Form.Item>
      </Form>
      <Alert type="info" message="휴가 유형 마스터의 단위·1회 총 시간 상한을 확장할 수 없습니다. 서버가 최종 정합성과 과거 사용 여부를 검사합니다." />
      {isApiError(mutation.error) && mutation.error.code === 'LEAVE_006' && <Alert type="warning" message="사용 이력이 있는 버전입니다. 새 버전을 만들어 설정하세요." />}
    </ActionModal>}
  </>;
}
