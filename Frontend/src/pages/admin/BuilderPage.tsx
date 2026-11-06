import { Skeleton, Tag } from 'antd';
import { Link, Route, Routes, useParams } from 'react-router-dom';
import { useWorkflowList } from '@/api/builder';
import { BuilderEditor } from '@/builder/BuilderEditor';
import { Card, EmptyState, PageHeader } from '@/ui/primitives';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { Workflow } from '@/ui/icons';
import { useViewport } from '@/shell/useViewport';
import { formatDateTime, serverUtc } from '@/format';
import s from '@/builder/builder.module.css';

/** 워크플로 목록. 새로 만들기·복제는 P2(시드 스크립트 scripts/seed-demo-workflows.mjs로 만든다) */
function WorkflowList() {
  const q = useWorkflowList();
  return (
    <>
      <PageHeader title="자동화 빌더" description="워크플로를 열어 노드를 연결하고, 검사한 뒤 테스트 실행합니다." />
      <Card>
        {q.isError ? <ErrorPanel error={q.error} onRetry={() => q.refetch()} />
          : !q.data ? <Skeleton active={false} paragraph={{ rows: 4 }} />
          : q.data.length === 0 ? <EmptyState icon={Workflow} title="워크플로가 없습니다" description="시드 스크립트로 시연 워크플로를 만듭니다(scripts/seed-demo-workflows.mjs)." />
          : (
            <ul className={s.list}>
              {q.data.map((w) => (
                <li key={w.id} className={s.row}>
                  <div className={s.rowMain}>
                    <Link to={`/admin/builder/${w.id}`} className={s.rowName}>{w.name}</Link>
                    {w.description && <div className={s.rowDesc}>{w.description}</div>}
                  </div>
                  {w.deployedVersion != null ? <Tag>v{w.deployedVersion} 배포됨</Tag> : <Tag>배포 전</Tag>}
                  <span className={`${s.muted} nx-num`}>수정 {formatDateTime(serverUtc(w.updatedAt), true)}</span>
                </li>
              ))}
            </ul>
          )}
      </Card>
    </>
  );
}

function EditorRoute() {
  const { id } = useParams();
  if (!id || !/^\d+$/.test(id)) return <EmptyState title="워크플로를 찾을 수 없습니다" />;
  return <BuilderEditor key={id} id={Number(id)} />;
}

/** 자동화 빌더(관리자, 설계 6.6). 1280px 이상에서만 연다(설계 8.3 7.5) */
export function BuilderPage() {
  const { band } = useViewport();
  if (band !== 'wide') {
    return <Card><EmptyState title="1280px 이상 화면에서 열립니다" description="창을 넓히거나 브라우저 확대를 100%로 맞춰 주세요(Ctrl+0)." /></Card>;
  }
  return (
    <Routes>
      <Route index element={<WorkflowList />} />
      <Route path=":id" element={<EditorRoute />} />
    </Routes>
  );
}

export default BuilderPage;
