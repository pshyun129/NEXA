import { useState } from 'react';
import { Button, Drawer, Table, Tag, Tooltip } from 'antd';
import { useDeployWorkflow, useWorkflowVersions, type WorkflowVersion } from '@/api/builder';
import { ConfirmAction } from '@/ui/ConfirmAction';
import { useNotify } from '@/ui/useNotify';
import { formatDateTime, serverUtc } from '@/format';
import s from './builder.module.css';

/**
 * 배포·버전 조회(P1, 설계 9장 "빌더 배포·버전 조회"). 버전 활성화(되돌리기)는 P2라 두지 않는다.
 * 배포는 저장 + 서버 검사 통과(이슈 0) 뒤에만 연다. 서버도 배포 때 다시 검사한다(WF_INVALID_GRAPH).
 */
export function DeployControls({ workflowId, blockedReason, warningCount, exposed, fileMissing, onDeployed }: {
  workflowId: number;
  /** 사원 업무 자동화 목록 노출(placements에 HOME) 여부. 꺼져 있으면 확인 창에 알린다 */
  exposed: boolean;
  /** 파일 업로드 노드가 있는데 입력 폼에 파일 칸이 없음 */
  fileMissing?: boolean;
  onDeployed?: (version: number) => void;
  /** 배포를 막는 이유(저장 안 됨, 검사 안 함, 이슈 있음). null이면 배포 가능 */
  blockedReason: string | null;
  /** 클라이언트 순서 경고 수(배포는 막지 않고 확인 창에 적는다) */
  warningCount: number;
}) {
  const notify = useNotify();
  const versions = useWorkflowVersions(workflowId);
  const deploy = useDeployWorkflow(workflowId);
  const [open, setOpen] = useState(false);
  const list = versions.data ?? [];
  const next = Math.max(0, ...list.map((v) => v.version)) + 1;

  return (
    <>
      <Button onClick={() => setOpen(true)}>버전{list.length > 0 && ` ${list.length}`}</Button>
      <ConfirmAction
        title={`v${next}로 배포합니다`}
        confirmLabel="배포"
        onConfirm={() => deploy.mutateAsync().then(
          (r) => { notify.success(`v${r.version}로 배포했습니다`); onDeployed?.(r.version); },
          (e) => notify.error(e, '배포하지 못했습니다'),
        )}
        trigger={(openConfirm) => (
          <Tooltip title={blockedReason}>
            <Button onClick={openConfirm} disabled={!!blockedReason || deploy.isPending} loading={deploy.isPending}>배포</Button>
          </Tooltip>
        )}
      >
        <p>저장된 초안을 v{next}로 배포합니다. 이후 시작하는 실행부터 이 버전이 쓰이고, 이미 시작된 실행은 원래 버전으로 계속 진행됩니다.</p>
        {warningCount > 0 && <p className={s.muted}>경고 {warningCount}건이 남아 있습니다(서버 검사는 통과).</p>}
        {fileMissing && <p className={s.muted}>⚠ 사원 입력 폼에 파일 칸이 없어 사원이 파일을 올릴 수 없습니다. [입력 폼]에서 추가해 주세요.</p>}
        {!exposed && <p className={s.muted}>[사원에게 노출]이 꺼져 있어 배포해도 사원 업무 자동화 목록에는 보이지 않습니다.</p>}
      </ConfirmAction>
      <Drawer open={open} onClose={() => setOpen(false)} width={420} title="배포 버전">
        <Table<WorkflowVersion>
          rowKey="version"
          size="small"
          pagination={false}
          loading={versions.isPending}
          dataSource={[...list].sort((a, b) => b.version - a.version)}
          locale={{ emptyText: '아직 배포한 버전이 없습니다' }}
          columns={[
            { title: '버전', dataIndex: 'version', width: 80, render: (v: number) => <span className="nx-num">v{v}</span> },
            { title: '배포 시각', dataIndex: 'createdAt', render: (v: string) => <span className="nx-num">{formatDateTime(serverUtc(v))}</span> },
            { title: '', dataIndex: 'active', width: 90, render: (a: boolean) => (a ? <Tag color="green">사용 중</Tag> : null) },
          ]}
        />
        <p className={s.muted} style={{ marginTop: 12 }}>이전 버전으로 되돌리기는 지원하지 않습니다.</p>
      </Drawer>
    </>
  );
}
