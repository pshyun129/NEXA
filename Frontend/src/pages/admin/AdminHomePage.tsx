import { Button, Skeleton } from 'antd';
import { Link } from 'react-router-dom';
import { useAllEmployees, useDepartments } from '@/api/org';
import { useBuilderWorkflows, useWorkflowList } from '@/api/builder';
import { Card, PageHeader } from '@/ui/primitives';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { formatCount } from '@/format';
import s from './adminHome.module.css';

function Stat({ label, value, tone, to }: { label: string; value: string; tone?: 'warning' | 'danger'; to?: string }) {
  const body = (
    <>
      <span className={`${s.value} ${tone ? s[tone] : ''} nx-num`}>{value}</span>
      <span className={s.label}>{label}</span>
    </>
  );
  return to ? <Link to={to} className={`${s.stat} ${s.link}`}>{body}</Link> : <div className={s.stat}>{body}</div>;
}

/** 인사: 재직 상태별 인원과 부서장이 비었거나 재직이 아닌 부서(결재선이 상위로 건너뛴다) */
function HrCard() {
  const emps = useAllEmployees();
  const depts = useDepartments();
  const err = emps.error ?? depts.error;
  let content;
  if (err) content = <ErrorPanel error={err} onRetry={() => { emps.refetch(); depts.refetch(); }} />;
  else if (!emps.data || !depts.data) content = <Skeleton active={false} paragraph={{ rows: 2 }} />;
  else {
    const by = (st: string) => emps.data.filter((e) => e.status === st).length;
    const employed = new Set(emps.data.filter((e) => e.status === 'EMPLOYED').map((e) => e.empId));
    const vacant = depts.data.filter((d) => d.headEmpId == null || !employed.has(d.headEmpId));
    const topVacant = vacant.some((d) => d.parentDeptId == null);
    content = (
      <div className={s.stats}>
        <Stat label="재직" value={formatCount(by('EMPLOYED'), '명')} to="/admin/hr/employees" />
        <Stat label="휴직" value={formatCount(by('ON_LEAVE'), '명')} />
        <Stat label="퇴직" value={formatCount(by('TERMINATED'), '명')} />
        <Stat label="부서" value={formatCount(depts.data.length, '개')} to="/admin/hr" />
        <Stat label={topVacant ? '부서장 공석 (최상위 포함)' : '부서장 공석'} value={formatCount(vacant.length, '개')}
              tone={topVacant ? 'danger' : vacant.length > 0 ? 'warning' : undefined} to="/admin/hr" />
      </div>
    );
  }
  return <Card title="인사" meta={<Link to="/admin/hr">인사 관리 →</Link>}>{content}</Card>;
}

/** 자동화: 배포됐지만 사원에게 노출되지 않은 워크플로는 사원 목록에 보이지 않는다(placements) */
function AutomationCard() {
  const list = useWorkflowList();
  const deployed = (list.data ?? []).filter((w) => w.deployedVersion != null);
  const details = useBuilderWorkflows(deployed.map((w) => w.id));
  const hidden = details.filter((d) => d.data && !d.data.placements.includes('HOME')).map((d) => d.data!);
  let content;
  if (list.isError) content = <ErrorPanel error={list.error} onRetry={() => list.refetch()} />;
  else if (!list.data) content = <Skeleton active={false} paragraph={{ rows: 2 }} />;
  else {
    content = (
      <>
        <div className={s.stats}>
          <Stat label="워크플로" value={formatCount(list.data.length, '개')} to="/admin/builder" />
          <Stat label="배포됨" value={formatCount(deployed.length, '개')} />
          <Stat label="배포됐지만 사원에게 안 보임" value={formatCount(hidden.length, '개')} tone={hidden.length ? 'warning' : undefined} />
        </div>
        {hidden.length > 0 && (
          <ul className={s.list}>
            {hidden.map((w) => (
              <li key={w.id}>
                <span>{w.name} <span className={s.muted}>v{w.deployedVersion}</span></span>
                <Link to={`/admin/builder/${w.id}`}><Button size="small">[사원에게 노출] 켜기 →</Button></Link>
              </li>
            ))}
          </ul>
        )}
      </>
    );
  }
  return <Card title="업무 자동화" meta={<Link to="/admin/builder">자동화 빌더 →</Link>}>{content}</Card>;
}

/** 이관: 작업 목록 API가 아직 develop에 없어 바로가기만 둔다(gaps) */
function MigrationCard() {
  return (
    <Card title="데이터 이관" meta={<Link to="/admin/migration">데이터 이관 →</Link>}>
      <div className={s.cta}>
        <span className={s.muted}>조직 파일 → 사원 파일 순서로 올려 매핑·검증·적재합니다.</span>
        <Link to="/admin/migration"><Button type="primary">새 이관 시작</Button></Link>
      </div>
    </Card>
  );
}

/** 관리 홈. 관리자용 현황 API가 있는 영역만 숫자로 보여 준다(근태·급여·휴가 관리는 P2·백엔드 갭) */
export function AdminHomePage() {
  return (
    <>
      <PageHeader title="관리 홈" />
      <div className={s.grid}>
        <HrCard />
        <AutomationCard />
        <MigrationCard />
      </div>
    </>
  );
}
