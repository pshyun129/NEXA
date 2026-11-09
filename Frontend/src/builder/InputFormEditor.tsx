import { useEffect, useState } from 'react';
import { Alert, Button, Drawer, Input, Select, Switch } from 'antd';
import type { FormField } from '@/api/ai';
import { useSaveInputForm } from '@/api/builder';
import { Icon } from '@/ui/Icon';
import { X } from '@/ui/icons';
import { useNotify } from '@/ui/useNotify';
import s from './builder.module.css';

const TYPES: { value: FormField['type']; label: string }[] = [
  { value: 'file', label: '파일' }, { value: 'text', label: '글자' }, { value: 'number', label: '숫자' },
  { value: 'date', label: '날짜' }, { value: 'select', label: '선택' },
];

/** 파일 업로드 노드가 있는데 file 칸이 없을 때 넣는 기본 칸 */
export const DEFAULT_FILE_FIELD: FormField = { key: 'files', label: '첨부 파일', type: 'file', required: true, options: [], prefill: null };

/** 새 칸 key: 실행 입력(inputs)의 키라 영문·숫자로 자동 생성하고 겹치지 않게 한다 */
function nextKey(fields: FormField[]) {
  let n = fields.length + 1;
  while (fields.some((f) => f.key === `field${n}`)) n++;
  return `field${n}`;
}

/**
 * 사원 실행 입력 폼 편집(설계 6.6 [입력 폼] — P2 중 칸 추가·삭제·종류·필수만 앞당김, 2026-11-09).
 * 사원 실행 화면(SchemaForm)과 테스트 실행이 이 목록을 그린다. 파일은 type=file 칸이 있어야 올릴 수 있다.
 * prefill(화면 데이터 자동 채우기)은 서버가 아직 채우지 않아(TODO) 편집하지 않고 그대로 둔다.
 */
export function InputFormEditor({ workflowId, fields, open, setOpen, needsFile }: {
  workflowId: number; fields: FormField[]; open: boolean; setOpen: (o: boolean) => void;
  /** 그래프에 파일 업로드 노드가 있음 */
  needsFile: boolean;
}) {
  const notify = useNotify();
  const save = useSaveInputForm(workflowId);
  const [draft, setDraft] = useState<FormField[]>(fields);
  // 열 때마다 서버 값으로 시작한다
  useEffect(() => { if (open) setDraft(fields); }, [open, fields]);

  const update = (i: number, patch: Partial<FormField>) => setDraft(draft.map((f, j) => (j === i ? { ...f, ...patch } : f)));
  const changed = JSON.stringify(draft) !== JSON.stringify(fields);
  const blank = draft.some((f) => !f.label.trim());
  const noOptions = draft.some((f) => f.type === 'select' && f.options.length === 0);
  const fileMissing = needsFile && !draft.some((f) => f.type === 'file');

  const submit = () => save.mutate(draft.map((f) => ({ ...f, label: f.label.trim(), options: f.type === 'select' ? f.options : [] })), {
    onSuccess: () => { notify.success('입력 폼을 저장했습니다', '사원 실행 화면에 바로 반영됩니다'); setOpen(false); },
    onError: (e) => notify.error(e, '입력 폼을 저장하지 못했습니다'),
  });

  return (
    <>
      <Button onClick={() => setOpen(true)}>입력 폼{fields.length > 0 && ` ${fields.length}`}</Button>
      <Drawer
        open={open}
        onClose={() => !save.isPending && setOpen(false)}
        width={560}
        title="사원 입력 폼"
        destroyOnHidden
        extra={<Button type="primary" disabled={!changed || blank || noOptions} loading={save.isPending} onClick={submit}>저장</Button>}
      >
        <div className={s.formEditor}>
          <p className={s.muted}>사원이 [실행]을 누르면 이 칸들을 채웁니다. 저장하면 배포 없이 바로 반영됩니다.</p>
          {fileMissing && (
            <Alert type="warning" showIcon message="그래프에 파일 업로드 노드가 있는데 파일 칸이 없습니다"
                   description="파일 칸이 없으면 사원이 파일을 올릴 수 없어 실행이 실패합니다."
                   action={<Button size="small" onClick={() => setDraft([DEFAULT_FILE_FIELD, ...draft])}>파일 칸 추가</Button>} />
          )}
          {draft.length === 0 && <p className={s.muted}>입력 칸이 없습니다. 사원은 아무것도 입력하지 않고 실행합니다.</p>}
          {draft.map((f, i) => (
            <div key={i} className={s.formRow}>
              <Input value={f.label} placeholder="칸 이름 (예: 영수증)" status={f.label.trim() ? undefined : 'error'}
                     onChange={(e) => update(i, { label: e.target.value })} aria-label={`${i + 1}번 칸 이름`} maxLength={50} />
              <Select<FormField['type']> value={f.type} options={TYPES} style={{ width: 96 }} aria-label={`${i + 1}번 칸 종류`}
                                         onChange={(type) => update(i, { type })} />
              <label className={s.formReq}>
                <Switch size="small" checked={f.required} onChange={(required) => update(i, { required })} aria-label={`${i + 1}번 칸 필수`} /> 필수
              </label>
              <Button size="small" type="text" icon={<Icon as={X} />} aria-label={`${i + 1}번 칸 삭제`}
                      onClick={() => setDraft(draft.filter((_, j) => j !== i))} />
              {f.type === 'select' && (
                <Select mode="tags" value={f.options} onChange={(options) => update(i, { options })} className={s.formOptions}
                        placeholder="선택지를 입력하고 Enter" status={f.options.length ? undefined : 'error'} aria-label={`${i + 1}번 칸 선택지`}
                        open={false} />
              )}
              <code className={s.formKey}>{f.key}</code>
            </div>
          ))}
          <div>
            <Button onClick={() => setDraft([...draft, { key: nextKey(draft), label: '', type: 'text', required: false, options: [], prefill: null }])}>
              + 칸 추가
            </Button>
          </div>
        </div>
      </Drawer>
    </>
  );
}
