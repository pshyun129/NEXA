import { useState } from 'react';
import { Button, Input, Modal, Select } from 'antd';
import { useReferrer, useResubmit, type DocumentDetail } from '@/api/approval';
import { useAllEmployees } from '@/api/org';
import { EmpName } from '@/ui/EmpName';
import { useNotify } from '@/ui/useNotify';
import s from './approval.module.css';

/**
 * 재기안(P1, 요구사항 3.4): 반려된 직접 기안만. 제목만 고쳐 다시 올리고, 결재선은 서버가 지금 조직도로 다시 만든다.
 * 확인 모달은 쓰지 않는다(되돌릴 수 없는 작업이 아니고, 제목 입력 자체가 확인이다).
 */
export function ResubmitButton({ doc }: { doc: DocumentDetail }) {
  const notify = useNotify();
  const resubmit = useResubmit(doc.documentId);
  const [open, setOpen] = useState(false);
  const [title, setTitle] = useState(doc.title);
  const round = Math.max(1, ...doc.histories.map((h) => h.round)) + 1;
  const ok = () => title.trim() && resubmit.mutate(title.trim(), {
    onSuccess: () => { setOpen(false); notify.success(`${round}차로 재기안했습니다`); },
    onError: (e) => notify.error(e, '재기안하지 못했습니다'),
  });
  return (
    <>
      <Button type="primary" onClick={() => setOpen(true)}>재기안</Button>
      <Modal
        open={open}
        title={`${round}차로 재기안합니다`}
        width={480}
        onCancel={() => !resubmit.isPending && setOpen(false)}
        okText="재기안"
        okButtonProps={{ disabled: !title.trim(), loading: resubmit.isPending }}
        onOk={ok}
        destroyOnHidden
      >
        <p className={s.muted}>결재선은 지금 조직도로 다시 만들어지고, 이전 차수 이력은 그대로 남습니다.</p>
        <Input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={255} showCount autoFocus
               onPressEnter={ok} aria-label="재기안 제목" />
      </Modal>
    </>
  );
}

/**
 * 참조자 추가·제거(P1, 요구사항 3.4 결재선 편집 중 참조자만). 기안자이고 진행 중일 때만.
 * 후보는 재직자 중 기안자·이미 결재선에 있는 사람을 뺀다. 그 밖의 규칙은 서버 메시지를 그대로 보여 준다.
 */
export function ReferrerEditor({ doc }: { doc: DocumentDetail }) {
  const notify = useNotify();
  const { add, remove } = useReferrer(doc.documentId);
  const emps = useAllEmployees();
  const [pick, setPick] = useState<number | null>(null);
  const inLine = new Set([doc.drafterId, ...doc.approvalLines.map((l) => l.empId)]);
  const refs = doc.approvalLines.filter((l) => l.role === 'REFERENCE');
  const options = (emps.data ?? [])
    .filter((e) => e.status === 'EMPLOYED' && !inLine.has(e.empId))
    .map((e) => ({ value: e.empId, label: [e.name, e.deptName, e.positionName].filter(Boolean).join(' · ') }));

  const doAdd = () => pick != null && add.mutate(pick, {
    onSuccess: () => { setPick(null); notify.success('참조자를 추가했습니다'); },
    onError: (e) => notify.error(e, '참조자를 추가하지 못했습니다'),
  });
  return (
    <div className={s.refEditor}>
      <div className={s.refChips}>
        {refs.length === 0 && <span className={s.muted}>참조자가 없습니다</span>}
        {refs.map((l) => (
          <span key={l.lineId} className={s.refChip}>
            <EmpName id={l.empId} />
            <button type="button" aria-label="참조자 빼기" disabled={remove.isPending}
                    onClick={() => remove.mutate(l.empId, { onError: (e) => notify.error(e, '참조자를 빼지 못했습니다') })}>×</button>
          </span>
        ))}
      </div>
      <div className={s.refAdd}>
        <Select<number>
          showSearch
          allowClear
          style={{ flex: 1, minWidth: 200 }}
          placeholder="참조자 검색"
          loading={emps.isPending}
          value={pick ?? undefined}
          onChange={(v) => setPick(v ?? null)}
          optionFilterProp="label"
          options={options}
          aria-label="참조자로 추가할 사원"
        />
        <Button onClick={doAdd} disabled={pick == null} loading={add.isPending}>추가</Button>
      </div>
    </div>
  );
}
