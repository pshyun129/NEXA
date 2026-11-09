import { useState } from 'react';
import { Button, Dropdown, Form, Input, Modal, Skeleton, Tag } from 'antd';
import { Link, Route, Routes, useNavigate, useParams } from 'react-router-dom';
import {
  useCreateWorkflow, useDeleteWorkflow, useDuplicateWorkflow, useUpdateWorkflow, useWorkflowList,
} from '@/api/builder';
import type { WorkflowSummary } from '@/api/ai';
import { BuilderEditor } from '@/builder/BuilderEditor';
import { Card, EmptyState, PageHeader } from '@/ui/primitives';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { ConfirmAction } from '@/ui/ConfirmAction';
import { useNotify } from '@/ui/useNotify';
import { Icon } from '@/ui/Icon';
import { CopyPlus, MoreHorizontal, Pencil, Plus, Trash2, Workflow } from '@/ui/icons';
import { useViewport } from '@/shell/useViewport';
import { formatDateTime, serverUtc } from '@/format';
import s from '@/builder/builder.module.css';

const TINTS = ['output', 'input', 'rule', 'ai', 'human'] as const;

/** 이름·설명 입력 창. 새로 만들기와 수정이 같이 쓴다 */
function WorkflowForm({ open, title, okLabel, initial, busy, onCancel, onSubmit }: {
  open: boolean; title: string; okLabel: string; busy: boolean;
  initial?: { name: string; description: string | null };
  onCancel: () => void; onSubmit: (v: { name: string; description: string | null }) => void;
}) {
  const [form] = Form.useForm<{ name: string; description?: string }>();
  return (
    <Modal open={open} title={title} onCancel={onCancel} destroyOnHidden okText={okLabel} cancelText="취소"
           confirmLoading={busy} onOk={() => form.submit()} width={480}>
      <Form form={form} layout="vertical" preserve={false} initialValues={initial ?? undefined}
            onFinish={(v) => onSubmit({ name: v.name.trim(), description: v.description?.trim() || null })}>
        <Form.Item name="name" label="이름" rules={[{ required: true, whitespace: true, message: '이름을 입력해 주세요' }, { max: 100, message: '100자 이내로 입력해 주세요' }]}>
          <Input autoFocus maxLength={100} placeholder="예: 영수증 경비 정산" />
        </Form.Item>
        <Form.Item name="description" label="설명" extra="사원 업무 자동화 목록과 실행 창에 함께 보입니다.">
          <Input.TextArea maxLength={500} autoSize={{ minRows: 2, maxRows: 4 }} placeholder="예: 영수증 한 장으로 결재 상신까지" />
        </Form.Item>
      </Form>
    </Modal>
  );
}

/** 카드 하나: 열기(카드 전체) + 수정·복제·삭제 메뉴 */
function WorkflowCard({ w, index }: { w: WorkflowSummary; index: number }) {
  const notify = useNotify();
  const navigate = useNavigate();
  const update = useUpdateWorkflow(w.id);
  const dup = useDuplicateWorkflow();
  const del = useDeleteWorkflow();
  const [editing, setEditing] = useState(false);
  const tint = TINTS[w.id % TINTS.length];

  return (
    <li className={`${s.row} ${s[`cat-${tint}`]}`} style={{ animationDelay: `${index * 40}ms` }}>
      <span className={s.rowArt} aria-hidden><Icon as={Workflow} size={20} /></span>
      <div className={s.rowMain}>
        <Link to={`/admin/builder/${w.id}`} className={s.rowName}>{w.name}</Link>
        <div className={s.rowDesc}>{w.description || '설명 없음'}</div>
      </div>
      <div className={s.rowMeta}>
        {w.deployedVersion != null ? <Tag color="green">v{w.deployedVersion} 배포됨</Tag> : <Tag>배포 전</Tag>}
        <span className="nx-num">수정 {formatDateTime(serverUtc(w.updatedAt), true)}</span>
      </div>
      <div className={s.rowTools}>
        <ConfirmAction
          title={`'${w.name}'을(를) 삭제합니다`}
          confirmLabel="삭제"
          danger
          onConfirm={() => del.mutateAsync(w.id).then(
            () => notify.success('워크플로를 삭제했습니다'),
            (e) => notify.error(e, '삭제하지 못했습니다'),
          )}
          trigger={(openDelete) => (
            <Dropdown
              trigger={['click']}
              placement="bottomRight"
              menu={{
                items: [
                  { key: 'edit', icon: <Icon as={Pencil} size={16} />, label: '이름·설명 수정', onClick: () => setEditing(true) },
                  {
                    key: 'dup', icon: <Icon as={CopyPlus} size={16} />, label: '복제',
                    onClick: () => dup.mutate(w.id, {
                      onSuccess: (copy) => { notify.success(`'${copy.name}'을(를) 만들었습니다`); navigate(`/admin/builder/${copy.id}`); },
                      onError: (e) => notify.error(e, '복제하지 못했습니다'),
                    }),
                  },
                  { type: 'divider' },
                  { key: 'del', icon: <Icon as={Trash2} size={16} />, label: '삭제', danger: true, onClick: openDelete },
                ],
              }}
            >
              <Button type="text" size="small" aria-label={`${w.name} 메뉴`} icon={<Icon as={MoreHorizontal} size={16} />} loading={dup.isPending} />
            </Dropdown>
          )}
        >
          <p>초안 그래프·입력 폼·배포 버전이 함께 지워지고 되돌릴 수 없습니다. 실행 기록이 있는 워크플로는 서버가 삭제를 막습니다.</p>
        </ConfirmAction>
      </div>
      <WorkflowForm
        open={editing} title="이름·설명 수정" okLabel="저장" busy={update.isPending}
        initial={{ name: w.name, description: w.description }}
        onCancel={() => setEditing(false)}
        onSubmit={(v) => update.mutate(v, {
          onSuccess: () => { notify.success('저장했습니다'); setEditing(false); },
          onError: (e) => notify.error(e, '저장하지 못했습니다'),
        })}
      />
    </li>
  );
}

/** 워크플로 목록(관리자). 새로 만들기·이름 수정·복제·삭제 */
function WorkflowList() {
  const q = useWorkflowList();
  const create = useCreateWorkflow();
  const notify = useNotify();
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);
  const newButton = <Button type="primary" icon={<Icon as={Plus} size={16} />} onClick={() => setCreating(true)}>새 워크플로</Button>;
  return (
    <>
      <PageHeader eyebrow="AUTOMATION BUILDER" title="자동화 빌더" description="워크플로를 열어 노드를 연결하고, 검사한 뒤 테스트 실행합니다." actions={newButton} />
      {q.isError ? <Card><ErrorPanel error={q.error} onRetry={() => q.refetch()} /></Card>
        : !q.data ? <Card><Skeleton active={false} paragraph={{ rows: 4 }} /></Card>
        : q.data.length === 0 ? (
          <Card><EmptyState icon={Workflow} title="워크플로가 없습니다" description="새 워크플로를 만들어 노드를 연결해 보세요." action={newButton} /></Card>
        ) : (
          <ul className={s.list}>
            {q.data.map((w, i) => <WorkflowCard key={w.id} w={w} index={i} />)}
          </ul>
        )}
      <WorkflowForm
        open={creating} title="새 워크플로" okLabel="만들기" busy={create.isPending}
        onCancel={() => setCreating(false)}
        onSubmit={(v) => create.mutate(v, {
          onSuccess: (wf) => { setCreating(false); navigate(`/admin/builder/${wf.id}`); },
          onError: (e) => notify.error(e, '만들지 못했습니다'),
        })}
      />
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
