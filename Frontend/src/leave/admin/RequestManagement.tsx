import { useState } from 'react';
import { Alert, Button, Drawer, Form, Input, Space, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { Link } from 'react-router-dom';
import { useAllEmployees } from '@/api/org';
import { leaveAdminApi, useAdminRequests, useAdminRequest, type AdminRequest, type AdminRequestDetail, type CancellationReceipt } from '@/api/leaveAdmin';
import { leaveDay } from '@/api/leave';
import { isApiError } from '@/api/ApiError';
import { Card, EmptyState } from '@/ui/primitives';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { StatusBadge } from '@/ui/StatusBadge';
import { useNotify } from '@/ui/useNotify';
import { formatDate, formatDateTime, formatLeaveAmount } from '@/format';
import { leavePortionLabel } from '@/status/labels';
import { ActionModal, employeeKeys, PendingIntentNotice, usePersistentAdminRequest } from './AdminForms';

function CancellationEditor({ request, onClose }: { request: AdminRequestDetail; onClose: () => void }) {
  const operation = usePersistentAdminRequest<{ reason: string }, CancellationReceipt>('cancel', request.requestId,
    (_key, body) => leaveAdminApi.cancel(request.requestId, body.reason),
    [...employeeKeys(request.employeeId), ['leaveAdmin', 'requests'], ['leaveAdmin', 'request', request.requestId], ['leave', 'summary'], ['leave', 'ledger'], ['leave', 'requests']],
    (result) => result.adminCancelStatus === 'COMPLETED');
  const [form] = Form.useForm<{ reason: string }>();
  const notify = useNotify();
  const finish = async (input: { reason: string }) => {
    const result = await operation.execute(input);
    if (result?.adminCancelStatus === 'COMPLETED') { notify.success(result.message); onClose(); }
  };
  const pending = operation.result?.adminCancelStatus === 'UNKNOWN' || operation.result?.adminCancelStatus === 'IN_PROGRESS';
  return <ActionModal title={`휴가 신청 #${request.requestId}을 관리자 취소합니다`} danger allowUnknownRetry pending={operation.pending} error={operation.error}
    label={operation.intent ? '같은 취소 요청 다시 확인' : '관리자 취소 확인'} onClose={onClose}
    onSubmit={() => operation.intent ? void finish(operation.intent.body) : form.submit()}>
    <Alert type="warning" message="승인된 휴가의 근태를 원복하고, 원복 확정 후 Leave에서 RESTORE·CANCELLED를 처리합니다. Approval 문서는 승인 상태로 유지합니다." />
    <PendingIntentNotice locked={operation.intent != null} />
    <Form form={form} layout="vertical" initialValues={operation.intent?.body} disabled={operation.pending || operation.intent != null} onFinish={finish}>
      <Form.Item name="reason" label="취소 사유" rules={[{ required: true, whitespace: true }, { max: 2000 }]}><Input.TextArea maxLength={2000} showCount /></Form.Item>
    </Form>
    {operation.result && <Alert type={operation.result.adminCancelStatus === 'COMPLETED' ? 'success' : 'warning'}
      message={operation.result.message} description={`처리 상태: ${operation.result.adminCancelStatus} · UUID: ${operation.result.adminCancelRequestId}`} />}
    {pending && <Alert type="info" message="결과 확인이 필요합니다. 현재 승인 상태와 사용량을 유지하며 저장된 동일 사유로 재확인합니다. 새 취소 의도나 Approval 철회 요청을 만들지 않습니다." />}
    {isApiError(operation.error) && operation.error.code === 'LEAVE_025' && <Alert type="info" message="근태 원복 연동이 비활성화되어 있습니다. 취소 완료가 아닙니다. 담당자에게 연동 설정을 확인하세요." />}
    {isApiError(operation.error) && operation.error.code === 'LEAVE_021' && <Alert type="warning" message="현재 신청 또는 기존 취소 결과가 취소를 허용하지 않습니다. 자동으로 새로운 원복 요청을 시작하지 않습니다." />}
  </ActionModal>;
}

export function AdminRequestView({ id }: { id: number }) {
  const query = useAdminRequest(id);
  const [cancelling, setCancelling] = useState(false);
  if (query.isError) return <ErrorPanel error={query.error} onRetry={() => query.refetch()} />;
  if (!query.data) return <p aria-busy>신청을 조회하고 있습니다…</p>;
  const request = query.data;
  const dates = [...request.dates].sort((a, b) => a.leaveDate.localeCompare(b.leaveDate));
  return <Space direction="vertical" style={{ width: '100%' }}>
    <Space wrap><strong>{request.leaveTypeName}</strong><StatusBadge domain="LeaveRequestStatus" value={request.status} />
      <Button size="small" loading={query.isFetching} onClick={() => query.refetch()}>새로 확인</Button></Space>
    <p>신청 #{id} · 직원 #{request.employeeId} · 정책 버전 #{request.policyVersionId}</p>
    <p>총 신청 {formatLeaveAmount(request.totalMinutes)} · 차감 {formatLeaveAmount(request.totalDeductMinutes)}</p>
    <p>신청 시각: {formatDateTime(request.submittedAt ?? request.createdAt)}</p>
    <p>사유: {request.reason || '없음'}</p>
    {request.approvalDocId != null && <p>결재 문서 #{request.approvalDocId} · <Link to={`/approvals/${request.approvalDocId}`}>결재 문서 보기</Link></p>}
    {request.cancelledAt && <Alert type="info" message={`취소 ${formatDateTime(request.cancelledAt)} · 처리자 #${request.cancelledBy ?? '—'}`} description={request.cancelReason ?? undefined} />}
    <Table rowKey="requestDateId" size="small" dataSource={dates} pagination={false} columns={[
      { title: '날짜', dataIndex: 'leaveDate', render: (value: string) => formatDate(leaveDay(value)) },
      { title: '단위', dataIndex: 'portion', render: (value: string) => leavePortionLabel(value) },
      { title: '차감', dataIndex: 'deductMinutes', render: (value: number) => formatLeaveAmount(value) },
    ]} />
    <Button danger disabled={request.status !== 'APPROVED'} onClick={() => setCancelling(true)}>승인 휴가 관리자 취소</Button>
    {request.status !== 'APPROVED' && <p>APPROVED 상태의 신청만 관리자 취소할 수 있습니다.</p>}
    <Alert type="info" message="조회 DTO에는 진행 중인 관리자 취소 UUID·사유가 없습니다. 이 탭에서 시작한 취소는 저장된 원문으로 재확인하며, 다른 브라우저에서 시작한 작업은 최초 관리자·사유 확인이 필요합니다." />
    {cancelling && <CancellationEditor request={request} onClose={() => setCancelling(false)} />}
  </Space>;
}

export function RequestManagement() {
  const [loaded, setLoaded] = useState(false);
  const [openId, setOpenId] = useState<number | null>(null);
  const requests = useAdminRequests(loaded);
  const employees = useAllEmployees();
  const names = new Map((employees.data ?? []).map((e) => [e.empId, e.name]));
  const columns: ColumnsType<AdminRequest> = [
    { title: '신청', dataIndex: 'requestId', width: 90 },
    { title: '직원', dataIndex: 'employeeId', render: (id: number) => `${names.get(id) ?? '직원'} (#${id})` },
    { title: '유형', dataIndex: 'leaveTypeName' },
    { title: '상태', dataIndex: 'status', render: (status: string) => <StatusBadge domain="LeaveRequestStatus" value={status} /> },
    { title: '차감', dataIndex: 'totalDeductMinutes', render: (minutes: number) => formatLeaveAmount(minutes) },
    { title: '신청 시각', key: 'at', render: (_, row) => formatDateTime(row.submittedAt ?? row.createdAt) },
    { title: '상세', key: 'detail', render: (_, row) => <Button size="small" onClick={() => setOpenId(row.requestId)}>상세 보기</Button> },
  ];
  return <Space direction="vertical" style={{ width: '100%' }}>
    <Alert type="info" showIcon message="현재 서버는 전체 신청 배열만 제공합니다. 직원·상태·기간 검색과 서버 페이징은 지원하지 않습니다."
      description="목록을 명시적으로 불러오며 자동 반복 조회하지 않습니다. 표의 페이지는 수신한 목록의 화면 분할입니다. 사용 날짜는 상세에서 확인하세요." />
    <Button loading={requests.isFetching} onClick={() => loaded ? requests.refetch() : setLoaded(true)}>전체 신청 {loaded ? '새로 조회' : '불러오기'}</Button>
    <Card title="휴가 신청 관리" flush>
      {!loaded ? <EmptyState title="전체 신청 불러오기를 눌러 조회하세요" /> : requests.isError ? <ErrorPanel error={requests.error} onRetry={() => requests.refetch()} />
        : <Table<AdminRequest> rowKey="requestId" loading={requests.isPending} columns={columns} dataSource={requests.data ?? []} pagination={{ pageSize: 20, showSizeChanger: false }} />}
    </Card>
    <Drawer open={openId != null} width={560} title="관리자 휴가 신청 상세" destroyOnHidden onClose={() => setOpenId(null)}>
      {openId != null && <AdminRequestView key={openId} id={openId} />}
    </Drawer>
  </Space>;
}
