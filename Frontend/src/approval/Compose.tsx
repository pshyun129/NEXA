import { useEffect, useRef, useState } from 'react';
import type { InputRef } from 'antd';
import { Alert, Button, Drawer, Input, InputNumber, Segmented, Skeleton } from 'antd';
import {
  useApprovalLinePreview, useCreateDocument,
  type ApprovalAttributes, type DraftFormType,
} from '@/api/approval';
import { EmpName } from '@/ui/EmpName';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { Kbd, modKey } from '@/ui/primitives';
import { useNotify } from '@/ui/useNotify';
import { formatNumber } from '@/format';
import { approvalLevelLabel } from '@/status/labels';
import s from './approval.module.css';

const FORMS: { label: string; value: DraftFormType }[] = [
  { label: '일반기안', value: 'GENERAL' },
  { label: '출장', value: 'BUSINESS_TRIP' },
];

/** 결재선 미리보기: 서버 응답 그대로(임계값을 화면에 두지 않는다, 요구사항 3.4) */
function LinePreview({ formType, attributes }: { formType: DraftFormType; attributes: ApprovalAttributes }) {
  const q = useApprovalLinePreview(formType, attributes);
  if (q.isError) return <ErrorPanel error={q.error} onRetry={() => q.refetch()} title="결재선을 만들지 못했습니다" />;
  if (!q.data) return <Skeleton active={false} paragraph={{ rows: 2 }} />;
  const p = q.data;
  return (
    <div className={s.preview} aria-busy={q.isFetching}>
      <div className={s.previewHead}>
        <b>결재선 미리보기</b>
        <span className={s.muted}>{approvalLevelLabel(p.approvalLevel)}</span>
      </div>
      {p.selfApproval ? (
        <Alert type="info" showIcon message="결재자 없이 상신하면 바로 승인됩니다(최상위 부서장 기안)." />
      ) : (
        <ol className={s.previewLine}>
          <li><span className={s.muted}>기안</span> 나</li>
          {p.approvalLines.map((l) => (
            <li key={`${l.step}-${l.empId}`}><span className={s.muted}>{l.step}단계 {l.role === 'AGREEMENT' ? '합의' : '결재'}</span> <EmpName id={l.empId} /></li>
          ))}
        </ol>
      )}
      <p className={s.muted}>참조자는 상신한 뒤 문서 상세에서 추가할 수 있습니다.</p>
    </div>
  );
}

/**
 * 직접 기안(P1, 요구사항 3.4 기안 화면): 양식(일반기안·출장) → 제목·속성 → 결재선 미리보기 → 상신.
 * 본문 편집기는 두지 않는다(요구사항 6장 #8: 본문·첨부 API 없음).
 */
export function ComposeDrawer({ open, onClose, onCreated }: { open: boolean; onClose: () => void; onCreated: (id: number) => void }) {
  const notify = useNotify();
  const create = useCreateDocument();
  const [formType, setFormType] = useState<DraftFormType>('GENERAL');
  const [title, setTitle] = useState('');
  const [amount, setAmount] = useState<number | null>(null);
  const [overseas, setOverseas] = useState(false);

  const attributes: ApprovalAttributes = formType === 'GENERAL' ? (amount != null ? { amount } : {}) : { overseas };
  // 금액을 칠 때마다 미리보기를 부르지 않도록 입력이 0.4초 멈추면 반영한다
  const [previewAmount, setPreviewAmount] = useState<number | null>(null);
  useEffect(() => {
    const t = setTimeout(() => setPreviewAmount(amount), 400);
    return () => clearTimeout(t);
  }, [amount]);
  const previewAttrs: ApprovalAttributes = formType === 'GENERAL' ? (previewAmount != null ? { amount: previewAmount } : {}) : { overseas };
  const canSubmit = title.trim().length > 0 && !create.isPending;

  const titleRef = useRef<InputRef>(null);
  // canSubmit은 렌더 시점 값이라 Ctrl+Enter 연타를 못 막는다. 제출 중 여부는 ref로 바로 막는다(QA APR-B1)
  const submitting = useRef(false);
  // 상신 한 건당 키 하나. 실패 후 다시 보내도 같은 키라 서버가 중복 생성하지 않는다. 성공하면 새 키
  const requestKey = useRef(crypto.randomUUID());

  const reset = () => { setTitle(''); setAmount(null); setOverseas(false); setFormType('GENERAL'); requestKey.current = crypto.randomUUID(); };
  const submit = () => {
    if (!canSubmit || submitting.current) return;
    submitting.current = true;
    create.mutate({ title: title.trim(), formType, attributes, requestKey: requestKey.current }, {
      onSuccess: (r) => { notify.success(`${r.docNo} 문서를 상신했습니다`); reset(); onCreated(r.documentId); },
      onError: (e) => notify.error(e, '상신하지 못했습니다'),
      onSettled: () => { submitting.current = false; },
    });
  };

  return (
    <Drawer
      open={open}
      onClose={onClose}
      width={520}
      title="기안하기"
      destroyOnHidden={false}
      // destroyOnHidden=false라 autoFocus는 첫 마운트 때만 동작한다. 열릴 때마다 제목에 포커스(QA APR-B2)
      afterOpenChange={(o) => { if (o) titleRef.current?.focus(); }}
      footer={
        <div className={s.composeFoot}>
          <Button onClick={onClose}>취소</Button>
          <Button type="primary" disabled={!canSubmit} loading={create.isPending} onClick={submit}>
            상신 <Kbd onPrimary>{modKey}+Enter</Kbd>
          </Button>
        </div>
      }
    >
      <form
        className={s.compose}
        onSubmit={(e) => { e.preventDefault(); submit(); }}
        onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); submit(); } }}
      >
        <label className={s.field}>
          <span>양식</span>
          <Segmented<DraftFormType> options={FORMS} value={formType} onChange={setFormType} />
        </label>
        <label className={s.field}>
          <span>제목</span>
          <Input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={255} showCount ref={titleRef}
                 placeholder={formType === 'GENERAL' ? '예: 팀 워크숍 비용 지출' : '예: 부산 고객사 방문'} aria-label="제목" />
        </label>
        {formType === 'GENERAL' ? (
          <label className={s.field}>
            <span>금액(선택)</span>
            <InputNumber<number> value={amount} onChange={(v) => setAmount(v ?? null)} min={0} step={10000} style={{ width: 240 }}
                                 formatter={(v) => (v == null || String(v) === '' ? '' : formatNumber(Number(v)))}
                                 parser={(v) => Number((v ?? '').replace(/[^\d]/g, ''))}
                                 addonAfter="원" aria-label="금액" />
            <span className={s.muted}>금액에 따라 결재 단계가 달라질 수 있습니다.</span>
          </label>
        ) : (
          <label className={s.field}>
            <span>출장지</span>
            <Segmented<'DOMESTIC' | 'OVERSEAS'>
              options={[{ label: '국내', value: 'DOMESTIC' }, { label: '해외', value: 'OVERSEAS' }]}
              value={overseas ? 'OVERSEAS' : 'DOMESTIC'}
              onChange={(v) => setOverseas(v === 'OVERSEAS')}
            />
          </label>
        )}
        <LinePreview formType={formType} attributes={previewAttrs} />
      </form>
    </Drawer>
  );
}
