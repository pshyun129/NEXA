import { useMemo, useState } from 'react';
import { Alert, Button, DatePicker, Input, Modal, Segmented, TreeSelect } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { useAllEmployees, useDepartments, useTerminateEmployee, type Department } from '@/api/org';
import type { Employee, EmploymentStatus } from '@/api/types';
import { useMyId } from '@/auth/session';
import { Card } from '@/ui/primitives';
import { DataTable } from '@/ui/DataTable';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { StatusBadge } from '@/ui/StatusBadge';
import { useNotify } from '@/ui/useNotify';
import { formatDateShort, formatNumber, nowKst } from '@/format';
import s from './admin.module.css';

type StatusFilter = EmploymentStatus | 'ALL';
const STATUS_FILTERS: { label: string; value: StatusFilter }[] = [
  { label: '재직', value: 'EMPLOYED' },
  { label: '휴직', value: 'ON_LEAVE' },
  { label: '퇴직', value: 'TERMINATED' },
  { label: '전체', value: 'ALL' },
];

interface TreeOpt { value: number; title: string; children: TreeOpt[] }
function deptTree(depts: Department[]): TreeOpt[] {
  const ids = new Set(depts.map((d) => d.deptId));
  const build = (parent: number | null): TreeOpt[] => depts
    .filter((d) => (d.parentDeptId != null && ids.has(d.parentDeptId) ? d.parentDeptId : null) === parent)
    .sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
    .map((d) => ({ value: d.deptId, title: d.name, children: build(d.deptId) }));
  return build(null);
}
function subtreeIds(depts: Department[], root: number): Set<number> {
  const out = new Set<number>([root]);
  let grew = true;
  while (grew) {
    grew = false;
    for (const d of depts) if (d.parentDeptId != null && out.has(d.parentDeptId) && !out.has(d.deptId)) { out.add(d.deptId); grew = true; }
  }
  return out;
}

/** 퇴직 처리 모달(설계 7.2 확인 모달: 동사형 제목, 영향 범위, danger, 기본 포커스 [취소]) */
function TerminateModal({ emp, headOf, onClose }: { emp: Employee; headOf: Department[]; onClose: () => void }) {
  const notify = useNotify();
  const terminate = useTerminateEmployee();
  const [date, setDate] = useState(nowKst().format('YYYY-MM-DD'));
  const [reason, setReason] = useState('');
  const ok = () => terminate.mutate({ empId: emp.empId, resignDate: date, resignReason: reason.trim() }, {
    onSuccess: () => { notify.success(`${emp.name} 님을 퇴직 처리했습니다`); onClose(); },
    onError: (e) => notify.error(e, '퇴직 처리하지 못했습니다'),
  });
  return (
    <Modal
      open
      title={`${emp.name} 님을 퇴직 처리합니다`}
      width={480}
      onCancel={() => !terminate.isPending && onClose()}
      maskClosable={!terminate.isPending}
      destroyOnHidden
      footer={[
        <Button key="cancel" autoFocus onClick={onClose} disabled={terminate.isPending}>취소</Button>,
        <Button key="ok" type="primary" danger loading={terminate.isPending} disabled={!date} onClick={ok}>퇴직 처리</Button>,
      ]}
    >
      <div className={s.stack} style={{ gap: 12 }}>
        <p style={{ margin: 0 }}>{emp.deptName ?? '—'} · {emp.empNo ?? `#${emp.empId}`}. 처리하면 바로 퇴직 상태가 되고 다시 로그인할 수 없습니다. 이미 로그인한 화면은 최대 15분 동안 유지될 수 있습니다.</p>
        {headOf.length > 0 && (
          <Alert type="warning" showIcon
                 message={`${headOf.map((d) => d.name).join(', ')} 부서장입니다`}
                 description="퇴직 후 결재선에서는 공석으로 처리되어 상위 부서장이 결재합니다. 부서 탭에서 새 부서장을 지정하세요." />
        )}
        <label className={s.field}>
          <span>퇴직일</span>
          <DatePicker value={dayjs(date)} allowClear={false} onChange={(v) => v && setDate(v.format('YYYY-MM-DD'))} aria-label="퇴직일" />
        </label>
        <label className={s.field}>
          <span>사유(선택)</span>
          <Input.TextArea value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} autoSize={{ minRows: 2, maxRows: 4 }} aria-label="퇴직 사유" />
        </label>
      </div>
    </Modal>
  );
}

/** 관리자 사원 목록·퇴직(P1, 요구사항 4.1). 서버가 전체 배열을 주므로 검색·필터는 화면에서 한다 */
export function EmployeeAdmin() {
  const me = useMyId();
  const emps = useAllEmployees();
  const depts = useDepartments();
  const [status, setStatus] = useState<StatusFilter>('EMPLOYED');
  const [dept, setDept] = useState<number | null>(null);
  const [target, setTarget] = useState<Employee | null>(null);

  const tree = useMemo(() => deptTree(depts.data ?? []), [depts.data]);
  const inDept = useMemo(() => (dept != null ? subtreeIds(depts.data ?? [], dept) : null), [dept, depts.data]);
  const rows = (emps.data ?? []).filter((e) => (status === 'ALL' || e.status === status) && (!inDept || inDept.has(e.deptId)));
  const headOf = (e: Employee) => (depts.data ?? []).filter((d) => d.headEmpId === e.empId);

  const cols: ColumnsType<Employee> = [
    {
      title: '이름', dataIndex: 'name', width: 120,
      render: (v: string, r) => <><b>{v}</b>{r.role === 'ADMIN' && <span className={s.type} style={{ marginLeft: 6 }}>관리자</span>}</>,
    },
    { title: '사번', dataIndex: 'empNo', width: 100, render: (v: string | null) => <span className="nx-mono">{v ?? '—'}</span> },
    {
      title: '부서', dataIndex: 'deptName',
      render: (v: string | null, r) => <>{v ?? '—'}{headOf(r).length > 0 && <span className={s.muted}> · 부서장</span>}</>,
    },
    { title: '직위', dataIndex: 'positionName', width: 80, render: (v: string | null) => v ?? '—' },
    { title: '직급', dataIndex: 'gradeName', width: 80, render: (v: string | null) => v ?? '—' },
    { title: '직책', dataIndex: 'dutyName', width: 80, render: (v: string | null) => v ?? '—' },
    { title: '입사일', dataIndex: 'hireDate', width: 110, render: (v: string | null) => <span className="nx-num">{formatDateShort(v)}</span> },
    { title: '상태', dataIndex: 'status', width: 80, render: (v: EmploymentStatus) => <StatusBadge domain="EmploymentStatus" value={v} /> },
    {
      title: '', key: 'act', width: 100,
      render: (_, r) => r.status === 'TERMINATED' ? null
        : r.empId === me ? <span className={s.muted}>본인</span>
        : <Button size="small" danger onClick={(e) => { e.stopPropagation(); setTarget(r); }}>퇴직 처리</Button>,
    },
  ];

  const err = emps.error ?? depts.error;
  return (
    <Card title="사원" meta={<span className={s.muted}>{formatNumber(rows.length)}명</span>}>
      {err ? <ErrorPanel error={err} onRetry={() => { emps.refetch(); depts.refetch(); }} /> : (
        <>
          <div className={s.row} style={{ marginBottom: 12 }}>
            <Segmented<StatusFilter> options={STATUS_FILTERS} value={status} onChange={setStatus} />
            <TreeSelect<number>
              style={{ width: 220 }}
              allowClear
              treeDefaultExpandAll
              placeholder="부서(하위 포함)"
              treeData={tree}
              value={dept ?? undefined}
              onChange={(v) => setDept(v ?? null)}
              aria-label="부서 필터"
            />
          </div>
          <DataTable<Employee>
            data={emps.data ? rows : undefined}
            loading={emps.isPending}
            rowKey="empId"
            columns={cols}
            searchText={(e) => [e.name, e.empNo, e.deptName, e.positionName, e.gradeName, e.dutyName, e.loginId].filter(Boolean).join(' ')}
            searchPlaceholder="이름·사번·부서·이메일 검색"
            emptyTitle="조건에 맞는 사원이 없습니다"
          />
        </>
      )}
      {target && <TerminateModal emp={target} headOf={headOf(target)} onClose={() => setTarget(null)} />}
    </Card>
  );
}
