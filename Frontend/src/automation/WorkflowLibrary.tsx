import { useState } from 'react';
import { Button, Dropdown, Form, Input, Modal, Segmented, Skeleton, Tag } from 'antd';
import {
  useCreateWorkflow, useDeleteWorkflow, useDuplicateWorkflow, useUpdateWorkflow, useWorkflowList,
} from '@/api/builder';
import type { WorkflowSummary } from '@/api/ai';
import { useMyId, useSession, isAdmin } from '@/auth/session';
import { EmptyState } from '@/ui/primitives';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { ConfirmAction } from '@/ui/ConfirmAction';
import { useNotify } from '@/ui/useNotify';
import { Icon } from '@/ui/Icon';
import { CopyPlus, MoreHorizontal, Pencil, Plus, Trash2, Workflow } from '@/ui/icons';
import { formatDateTime, serverUtc } from '@/format';
import s from './hub.module.css';

export const TINTS = ['output', 'input', 'rule', 'ai', 'human'] as const;
export const tintOf = (id: number) => TINTS[id % TINTS.length];

/** 이름·설명 입력 창. 새로 만들기와 수정이 같이 쓴다 */
export function WorkflowForm({ open, title, okLabel, initial, busy, onCancel, onSubmit }: {
  open: boolean; title: string; okLabel: string; busy: boolean;
  initial?: { name: string; description: string | null };
  onCancel: () => void; onSubmit: (v: { name: string; description: string | null }) => void;
}) {
  const [form] = Form.useForm<{ name: string; description?: string }>();
  return (
    <Modal open={open} title={title} onCancel={onCancel} destroyOnHidden okText={okLabel} cancelText="취소"
           confirmLoading={busy} onOk={() => form.submit()} width={480} zIndex={1100}>
      <Form form={form} layout="vertical" preserve={false} initialValues={initial ?? undefined}
            onFinish={(v) => onSubmit({ name: v.name.trim(), description: v.description?.trim() || null })}>
        <Form.Item name="name" label="이름" rules={[{ required: true, whitespace: true, message: '이름을 입력해 주세요' }, { max: 100, message: '100자 이내로 입력해 주세요' }]}>
          <Input autoFocus maxLength={100} placeholder="예: 영수증 경비 정산" />
        </Form.Item>
        <Form.Item name="description" label="설명" extra="업무 자동화 목록과 실행 창에 함께 보입니다.">
          <Input.TextArea maxLength={500} autoSize={{ minRows: 2, maxRows: 4 }} placeholder="예: 영수증 한 장으로 결재 상신까지" />
        </Form.Item>
      </Form>
    </Modal>
  );
}

/** 카드 하나: 열기(카드 전체) + 수정·복제·삭제 메뉴 */
function WorkflowCard({ w, index, onOpen }: { w: WorkflowSummary; index: number; onOpen: (id: number) => void }) {
  const notify = useNotify();
  const update = useUpdateWorkflow(w.id);
  const dup = useDuplicateWorkflow();
  const del = useDeleteWorkflow();
  const [editing, setEditing] = useState(false);

  return (
    <li className={`${s.wfCard} ${s[`t-${tintOf(w.id)}`]}`} style={{ animationDelay: `${index * 40}ms` }}>
      <button type="button" className={s.wfOpen} onClick={() => onOpen(w.id)} aria-label={`${w.name} 열기`} />
      <span className={s.wfArt} aria-hidden><Icon as={Workflow} size={20} /></span>
      <strong className={s.wfName}>{w.name}</strong>
      <span className={s.wfDesc}>{w.description || '설명 없음'}</span>
      <span className={s.wfMeta}>
        {w.deployedVersion != null ? <Tag color="green">v{w.deployedVersion} 배포됨</Tag> : <Tag>배포 전</Tag>}
        <span className="nx-num">수정 {formatDateTime(serverUtc(w.updatedAt), true)}</span>
      </span>
      <div className={s.wfTools}>
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
                      onSuccess: (copy) => { notify.success(`'${copy.name}'을(를) 만들었습니다`); onOpen(copy.id); },
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

/** 새 워크플로 버튼 + 이름 입력 창. 만들면 바로 빌더로 연다 */
export function NewWorkflowButton({ onCreated, block }: { onCreated: (id: number) => void; block?: boolean }) {
  const create = useCreateWorkflow();
  const notify = useNotify();
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button type="primary" block={block} icon={<Icon as={Plus} size={16} />} onClick={() => setOpen(true)}>새 워크플로</Button>
      <WorkflowForm
        open={open} title="새 워크플로" okLabel="만들고 캔버스 열기" busy={create.isPending}
        onCancel={() => setOpen(false)}
        onSubmit={(v) => create.mutate(v, {
          onSuccess: (wf) => { setOpen(false); onCreated(wf.id); },
          onError: (e) => notify.error(e, '만들지 못했습니다'),
        })}
      />
    </>
  );
}

/**
 * 내가 만든 워크플로(created_by = 나). 모든 사원이 만들고 고칠 수 있다.
 * 관리자는 [전체]로 다른 사람이 만든 워크플로도 볼 수 있다.
 */
export function WorkflowLibrary({ onOpen }: { onOpen: (id: number) => void }) {
  const q = useWorkflowList();
  const myId = useMyId();
  const admin = isAdmin(useSession((st) => st.me));
  const [scope, setScope] = useState<'mine' | 'all'>('mine');
  const list = (q.data ?? []).filter((w) => scope === 'all' || w.createdBy === String(myId));

  return (
    <div className={s.library}>
      {admin && (
        <Segmented<'mine' | 'all'> value={scope} onChange={setScope} style={{ marginBottom: 16 }}
                                   options={[{ label: '내가 만든 워크플로', value: 'mine' }, { label: '전체(관리자)', value: 'all' }]} />
      )}
      {q.isError ? <ErrorPanel error={q.error} onRetry={() => q.refetch()} />
        : !q.data ? <Skeleton active={false} paragraph={{ rows: 4 }} />
        : list.length === 0 ? (
          <EmptyState icon={Workflow} title="아직 만든 워크플로가 없어요"
                      description="[새 워크플로]로 노드를 연결해 나만의 자동화를 만들어 보세요."
                      action={<NewWorkflowButton onCreated={onOpen} />} />
        ) : (
          <ul className={s.wfGrid}>
            {list.map((w, i) => <WorkflowCard key={w.id} w={w} index={i} onOpen={onOpen} />)}
          </ul>
        )}
    </div>
  );
}
