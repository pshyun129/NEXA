import { Tabs } from 'antd';
import { useLocation, useNavigate } from 'react-router-dom';
import { PageHeader } from '@/ui/primitives';
import { DepartmentAdmin } from '@/admin/DepartmentAdmin';
import { EmployeeAdmin } from '@/admin/EmployeeAdmin';

const TABS = [
  { key: '', label: '부서' },
  { key: 'employees', label: '사원' },
];

/** 관리자 인사(요구사항 4.1): /admin/hr 부서(트리·부서장·유형), /admin/hr/employees 사원(목록·퇴직) */
export function HrPage() {
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const sub = pathname.replace(/^\/admin\/hr\/?/, '').split('/')[0] ?? '';
  const active = TABS.some((t) => t.key === sub) ? sub : '';
  return (
    <>
      <PageHeader title="인사" description="부서장·부서 유형은 결재선을 만드는 기준입니다." />
      <Tabs activeKey={active} onChange={(k) => navigate(k ? `/admin/hr/${k}` : '/admin/hr')}
            items={TABS.map((t) => ({ key: t.key, label: t.label }))} />
      {active === '' ? <DepartmentAdmin /> : <EmployeeAdmin />}
    </>
  );
}
