import { useState } from 'react';
import { Skeleton, Switch } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { DEPT_TYPE_LABEL, useAllEmployees, useOrgChain } from '@/api/org';
import type { Employee } from '@/api/types';
import { useMyId } from '@/auth/session';
import { Card, PageHeader } from '@/ui/primitives';
import { DataTable } from '@/ui/DataTable';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { EmpName } from '@/ui/EmpName';
import { StatusBadge } from '@/ui/StatusBadge';
import s from './org.module.css';

/**
 * 결재 라인(소속 → 최상위). 공석(휴직·퇴직 포함)과 본인이 부서장인 단계는 건너뛰고 위로 올라간다(approval ApproverResolver).
 * 실제로 몇 단계까지 올라가는지는 문서 종류·금액에 따라 다르므로 상신 화면의 결재선 미리보기가 최종이다.
 */
function ApprovalChain({ empId }: { empId: number }) {
  const q = useOrgChain(empId);
  if (q.isError) return <ErrorPanel error={q.error} onRetry={() => q.refetch()} />;
  if (!q.data) return <Skeleton active={false} paragraph={{ rows: 4 }} />;
  return (
    <>
      <ol className={s.chain}>
        {q.data.chain.map((u, i) => (
          <li key={u.deptId} className={s.unit}>
            <span className={s.step}>{i === 0 ? '소속' : `${i}단계 위`}</span>
            <span className={s.dept}>
              <b>{u.deptName}</b>
              {u.deptType && <span className={s.type}>{DEPT_TYPE_LABEL[u.deptType]}</span>}
            </span>
            <span className={s.head}>
              {u.headEmpId == null ? <span className={s.muted}>공석 · 위로 올라감</span>
                : u.headEmpId === empId ? <span className={s.muted}>본인 · 건너뜀</span>
                : <EmpName id={u.headEmpId} />}
            </span>
          </li>
        ))}
      </ol>
      <p className={s.muted}>문서 종류와 금액에 따라 필요한 단계까지만 결재가 올라갑니다. 정확한 결재선은 상신 화면의 미리보기에서 확인하세요.</p>
    </>
  );
}

/** 조직도(P1, 요구사항 4.1 "사원 메뉴 조직도"): 결재 라인 + 사원 검색. 사원을 고르면 그 사람의 결재 라인 */
export function OrgPage() {
  const me = useMyId();
  const emps = useAllEmployees();
  const [picked, setPicked] = useState<Employee | null>(null);
  const [withTerminated, setWithTerminated] = useState(false);
  const target = picked?.empId ?? me;

  const rows = (emps.data ?? []).filter((e) => withTerminated || e.status !== 'TERMINATED');
  const cols: ColumnsType<Employee> = [
    { title: '이름', dataIndex: 'name', width: 110, render: (v: string, r) => <b>{v}{r.empId === me && <span className={s.muted}> (나)</span>}</b> },
    { title: '부서', dataIndex: 'deptName', render: (v: string | null) => v ?? '—' },
    { title: '직위', dataIndex: 'positionName', width: 90, render: (v: string | null) => v ?? '—' },
    { title: '직책', dataIndex: 'dutyName', width: 90, render: (v: string | null) => v ?? '—' },
    { title: '사번', dataIndex: 'empNo', width: 100, render: (v: string | null) => <span className="nx-mono">{v ?? '—'}</span> },
    { title: '상태', dataIndex: 'status', width: 80, render: (v: Employee['status']) => <StatusBadge domain="EmploymentStatus" value={v} /> },
  ];
  return (
    <>
      <PageHeader title="조직도" />
      <div className={s.split}>
        <Card
          title={picked && picked.empId !== me ? `${picked.name}의 결재 라인` : '내 결재 라인'}
          meta={picked && picked.empId !== me ? <button type="button" className={s.link} onClick={() => setPicked(null)}>내 결재 라인 보기</button> : undefined}
        >
          {target != null ? <ApprovalChain key={target} empId={target} /> : <Skeleton active={false} paragraph={{ rows: 4 }} />}
        </Card>
        <Card title="사원 검색" meta={
          <label className={s.toggle}><Switch size="small" checked={withTerminated} onChange={setWithTerminated} /> 퇴직자 포함</label>
        }>
          {emps.isError ? <ErrorPanel error={emps.error} onRetry={() => emps.refetch()} /> : (
            <DataTable<Employee>
              data={emps.data ? rows : undefined}
              loading={emps.isPending}
              rowKey="empId"
              columns={cols}
              searchText={(e) => [e.name, e.deptName, e.positionName, e.dutyName, e.empNo].filter(Boolean).join(' ')}
              searchPlaceholder="이름·부서·직위·사번 검색"
              emptyTitle="사원이 없습니다"
              rowClassName={(e) => (e.empId === target ? s.rowPicked : '')}
              onRow={(e) => ({
                onClick: () => setPicked(e),
                onKeyDown: (ev) => { if (ev.key === 'Enter') setPicked(e); },
                tabIndex: 0,
                style: { cursor: 'pointer' },
              })}
            />
          )}
        </Card>
      </div>
    </>
  );
}
