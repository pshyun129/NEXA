import { useState } from 'react';
import { Alert, Button, Checkbox, Form, Input, InputNumber, Select, Switch } from 'antd';
import { leaveAdminApi, useLeaveAdminMutation, type AdminLeaveType, type LeaveTypeInput } from '@/api/leaveAdmin';
import { useNotify } from '@/ui/useNotify';
import { ActionModal, positiveId, requiredName } from './AdminForms';

export function LeaveTypeEditor({ type, types }: { type?: AdminLeaveType; types: AdminLeaveType[] }) {
  const [open, setOpen] = useState(false);
  const [advanced, setAdvanced] = useState(false);
  const [form] = Form.useForm<LeaveTypeInput>();
  const mode = Form.useWatch('balanceMode', form);
  const notify = useNotify();
  const mutation = useLeaveAdminMutation((values: LeaveTypeInput) => {
    if (type && !advanced) return leaveAdminApi.updateType(type.leaveTypeId, { name: values.name, isActive: values.isActive });
    const input: LeaveTypeInput = { name: values.name, isPaid: values.isPaid, isActive: values.isActive,
      balanceMode: values.balanceMode, requestUnit: values.requestUnit, maxMinutes: values.maxMinutes ?? null,
      balanceTypeId: values.balanceMode === 'ACCOUNT' ? values.balanceTypeId : values.balanceTypeId ?? null };
    return type ? leaveAdminApi.updateType(type.leaveTypeId, input) : leaveAdminApi.createType(input);
  }, [['leaveAdmin', 'types'], ['leaveAdmin', 'typeRules'], ['leave', 'types']]);
  const balances = [...new Map(types.flatMap((t) => t.balanceType ? [[t.balanceType.balanceTypeId, t.balanceType] as const] : [])).values()];
  return <><Button size={type ? 'small' : 'middle'} onClick={() => setOpen(true)}>{type ? '수정' : '휴가 유형 생성'}</Button>
    {open && <ActionModal title={type ? `${type.name} 유형을 수정합니다` : '휴가 유형을 생성합니다'} pending={mutation.isPending} error={mutation.error}
      onClose={() => setOpen(false)} onSubmit={() => form.submit()}>
      {type && <Checkbox checked={advanced} onChange={(e) => setAdvanced(e.target.checked)}>업무 설정도 수정 (참조 데이터가 있으면 서버가 거부합니다)</Checkbox>}
      <Form form={form} clearOnDestroy disabled={mutation.isPending} layout="vertical" initialValues={type ? { ...type, isPaid: type.paid, balanceTypeId: type.balanceType?.balanceTypeId ?? null }
        : { name: '', isPaid: true, balanceMode: 'NONE', balanceTypeId: null, requestUnit: 'MIXED', maxMinutes: null, isActive: true }}
        onFinish={(values) => mutation.mutate(values, { onSuccess: () => { notify.success('휴가 유형을 저장했습니다'); setOpen(false); } })}>
        <Form.Item name="name" label="휴가 유형 이름" rules={requiredName}><Input maxLength={100} /></Form.Item>
        <Form.Item name="isActive" label="활성 상태" valuePropName="checked"><Switch /></Form.Item>
        <Form.Item name="isPaid" label="유급" valuePropName="checked"><Switch disabled={!!type && !advanced} /></Form.Item>
        <Form.Item name="balanceMode" label="잔액 차감 방식" rules={[{ required: true }]}><Select disabled={!!type && !advanced}
          options={[{ value: 'ACCOUNT', label: '잔액 계정 차감' }, { value: 'NONE', label: '잔액 미차감' }]} /></Form.Item>
        <Form.Item label="기존 연결된 잔액 유형 선택"><Select allowClear disabled={!!type && !advanced} options={balances.map((b) => ({ value: b.balanceTypeId, label: `${b.name} (#${b.balanceTypeId})` }))}
          onChange={(id) => form.setFieldValue('balanceTypeId', id ?? null)} /></Form.Item>
        <Form.Item name="balanceTypeId" label="잔액 유형 ID (ACCOUNT 필수)" rules={mode === 'ACCOUNT' ? positiveId : [{ type: 'integer', min: 1 }]}><InputNumber disabled={!!type && !advanced} min={1} /></Form.Item>
        <Form.Item name="requestUnit" label="신청 단위" rules={[{ required: true }]}><Select disabled={!!type && !advanced}
          options={[{ value: 'DAY', label: '일' }, { value: 'HALF_DAY', label: '반일' }, { value: 'HOUR', label: '시간' }, { value: 'MIXED', label: '혼합' }]} /></Form.Item>
        <Form.Item name="maxMinutes" label="1회 총 신청 상한 (분, 비우면 제한 없음)" rules={[{ type: 'integer', min: 1 }]}><InputNumber disabled={!!type && !advanced} min={1} /></Form.Item>
      </Form>
      <Alert type="info" message="잔액 유형 전체 목록 API는 없습니다. 기존 유형에 연결된 목록을 선택하거나 실제 잔액 유형 ID를 입력하세요. ID의 존재 여부는 서버가 검증합니다." />
      <Alert type="warning" message="사용 중인 유형은 이름과 활성 상태만 실제 변경할 수 있습니다. 비활성화해도 과거 신청과 원장은 보존됩니다." />
    </ActionModal>}
  </>;
}

export function DisableLeaveType({ type }: { type: AdminLeaveType }) {
  const [open, setOpen] = useState(false);
  const notify = useNotify();
  const mutation = useLeaveAdminMutation(() => leaveAdminApi.updateType(type.leaveTypeId, { isActive: false }), [['leaveAdmin', 'types'], ['leave', 'types']]);
  return <><Button size="small" danger disabled={!type.isActive} onClick={() => setOpen(true)}>비활성화</Button>
    {open && <ActionModal title={`${type.name} 유형을 비활성화합니다`} danger label="비활성화" pending={mutation.isPending} error={mutation.error}
      onClose={() => setOpen(false)} onSubmit={() => mutation.mutate(undefined, { onSuccess: () => { notify.success('비활성화했습니다'); setOpen(false); } })}>
      <p>신규 신청을 제한하며 기존 신청·발생분·원장 이력을 삭제하지 않습니다.</p>
    </ActionModal>}
  </>;
}
