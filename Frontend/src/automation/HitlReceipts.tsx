import { useMemo, useState } from 'react';
import { Alert, Button, Input, InputNumber, Pagination, Segmented, Select, Switch } from 'antd';
import {
  runFileDownloadUrl, usePolicyPreview, useResumeRun,
  type HitlReceipt, type HitlReceiptAnswer, type HitlWaiting, type PolicyPreview,
} from '@/api/ai';
import { useNotify } from '@/ui/useNotify';
import { Kbd, modKey } from '@/ui/primitives';
import { Icon } from '@/ui/Icon';
import { ChevronDown, Eye } from '@/ui/icons';
import { usePreviewStore } from './previewStore';
import { FilePreview, type PreviewTarget } from './FilePreview';
import { PURPOSES } from './ReceiptFactsTable';
import s from './automation.module.css';

const PAGE = 20;
/** 규정 검토가 묻는 사실(규정 용어) → 화면 칸 */
const NEED_TO_FIELD: Record<string, keyof Facts> = {
  '사용 목적': 'purpose', '참석 인원': 'headcount', '참석자 이름': 'attendees', '사용 사유': 'reason',
};

interface Facts { purpose?: string; headcount?: number | null; attendees?: string; reason?: string }
interface Draft { store: string; total: number | null; date: string; facts: Facts; include: boolean; note: string }

const initial = (r: HitlReceipt): Draft => ({
  store: String(r.fields.store ?? ''),
  total: r.fields.total == null || r.fields.total === '' ? null : Number(r.fields.total),
  date: String(r.fields.date ?? ''),
  facts: {
    purpose: r.facts.purpose, attendees: r.facts.attendees, reason: r.facts.reason,
    headcount: r.facts.headcount == null || r.facts.headcount === '' ? null : Number(r.facts.headcount),
  },
  include: r.include, note: r.note ?? '',
});

/** 처음 값과 달라진 것만 응답으로 */
function answerOf(r: HitlReceipt, d: Draft): HitlReceiptAnswer | null {
  const base = initial(r);
  const a: HitlReceiptAnswer = { index: r.index };
  const data: Record<string, unknown> = {};
  if (d.store.trim() !== base.store) data.store = d.store.trim();
  if (d.total !== base.total) data.total = d.total;
  if (d.date.trim() !== base.date) data.date = d.date.trim();
  if (Object.keys(data).length) a.data = data;
  const facts: Facts = {};
  (Object.keys(d.facts) as (keyof Facts)[]).forEach((k) => {
    if ((d.facts[k] ?? '') !== (base.facts[k] ?? '')) (facts as Record<string, unknown>)[k] = d.facts[k];
  });
  if (Object.keys(facts).length) a.facts = facts as HitlReceiptAnswer['facts'];
  if (d.include !== base.include) a.include = d.include;
  if (d.note.trim() !== base.note) a.note = d.note.trim();
  return Object.keys(a).length > 1 ? a : null;
}

function VerdictTag({ verdict, flagged, mode }: { verdict: HitlReceipt['verdict']; flagged: boolean; mode?: string }) {
  const v = mode === 'policy' ? verdict ?? '준수' : flagged ? '확인 필요' : '자동 확정';
  const cls = v === '위반' ? s.vBad : v === '확인 필요' ? s.vWarn : s.vOk;
  return <span className={`${s.vTag} ${cls}`}>{v}</span>;
}

/** 품목·찍힌 인원 → 참석 인원 칸 도움말 (품목 추정은 판정에 쓰지 않고 참고만) */
function headHint(r: HitlReceipt) {
  if (r.headcount?.printed) return `영수증에 찍힌 인원 ${r.headcount.printed}명`;
  const e = r.headcount?.estimate;
  return e ? `품목 기준 ${e.min === e.max ? e.min : `${e.min}~${e.max}`}명 (${e.basis}) · 참고용` : '영수증에서 인원을 알 수 없어요';
}

function ReceiptCard({ r, d, mode, open, canEdit, preview, documentId, onToggle, onChange, onPreview }: {
  r: HitlReceipt; d: Draft; mode?: string; open: boolean; canEdit: boolean; preview?: PolicyPreview; documentId?: number;
  onToggle: () => void; onChange: (d: Draft) => void; onPreview: (t: PreviewTarget) => void;
}) {
  const local = usePreviewStore((st) => (r.file_id != null ? st.byFile[r.file_id] : undefined));
  const needed = new Set((preview?.needed_facts ?? r.needed_facts).map((n) => NEED_TO_FIELD[n]).filter(Boolean));
  const reasons = preview?.reasons ?? r.reasons;
  const setFact = (k: keyof Facts, v: unknown) => onChange({ ...d, facts: { ...d.facts, [k]: v } });
  const imgUrl = r.file_id != null ? local?.url ?? runFileDownloadUrl(r.file_id, documentId) : null;
  const gms = r.gms_fields;
  const gmsDiff = (k: string, v: unknown) => gms && k in gms && String(gms[k] ?? '').replace(/\s/g, '') !== String(v ?? '').replace(/\s/g, '');
  const total = d.total;

  return (
    <li className={[s.rc, !d.include ? s.rcOut : '', r.flagged ? s.rcFlag : ''].join(' ')}>
      <button type="button" className={s.rcHead} onClick={onToggle} aria-expanded={open}>
        <span className={s.rcNo}>No.{r.index + 1}</span>
        <b className={s.rcStore}>{d.store || '상호 없음'}</b>
        <span className="nx-num">{total != null ? `${total.toLocaleString('ko-KR')}원` : '금액 없음'}</span>
        <span className={s.muted}>{d.date}</span>
        <span className={s.spacer} />
        {preview && <span className={s.muted}>다시 검토 →</span>}
        <VerdictTag verdict={preview?.verdict ?? r.verdict} flagged={r.flagged} mode={mode} />
        {!d.include && <span className={`${s.vTag} ${s.vOut}`}>정산 제외</span>}
        <Icon as={ChevronDown} size={16} style={{ transform: open ? 'rotate(180deg)' : undefined, transition: 'transform .2s' }} />
      </button>
      {open && (
        <div className={s.rcBody}>
          {reasons.length > 0 && <ul className={s.reasons}>{reasons.map((x) => <li key={x}>{x}</li>)}</ul>}
          {r.duplicate_of != null && <Alert type="warning" showIcon message={`No.${r.duplicate_of + 1}과 같은 결제로 보여요. 중복이면 정산에서 빼 주세요.`} />}
          {r.duplicate_prev && (
            <Alert type="error" showIcon
                   message={`이미 정산 신청한 영수증이에요 (실행 #${r.duplicate_prev.run_id} · ${r.duplicate_prev.state}${r.duplicate_prev.doc_no ? ` · ${r.duplicate_prev.doc_no}` : ''})`}
                   description="같은 영수증이면 [정산에 포함]을 꺼 주세요. 다른 결제라면 결재자에게 남길 설명에 이유를 적어 주세요." />
          )}
          <div className={s.rcGrid}>
            {imgUrl && (
              <button type="button" className={s.rcThumb} title="원본 크게 보기"
                      onClick={() => onPreview({ fileId: r.file_id!, name: r.name ?? `영수증 ${r.index + 1}`, contentType: 'image/*', localUrl: local?.url })}>
                <img src={imgUrl} alt={r.name ?? ''} />
                <span><Icon as={Eye} size={12} /> 원본</span>
              </button>
            )}
            <div className={s.rcFields}>
              <label>상호
                <Input size="small" disabled={!canEdit} value={d.store} onChange={(e) => onChange({ ...d, store: e.target.value })}
                       status={gmsDiff('store', d.store) ? 'warning' : undefined} aria-label={`No.${r.index + 1} 상호`} />
                {gmsDiff('store', d.store) && <small className={s.muted}>교차 추출: {String(gms!.store)}</small>}
              </label>
              <label>금액
                <InputNumber size="small" disabled={!canEdit} value={total} min={0} style={{ width: '100%' }}
                             status={total == null ? 'error' : gmsDiff('total', total) ? 'warning' : undefined}
                             formatter={(v) => (v == null || String(v) === '' ? '' : Number(v).toLocaleString('ko-KR'))}
                             parser={(v) => (v ? Number(v.replace(/[^\d]/g, '')) : (null as unknown as number))}
                             onChange={(v) => onChange({ ...d, total: v ?? null })} aria-label={`No.${r.index + 1} 금액`} />
                {gmsDiff('total', total) && <small className={s.muted}>교차 추출: {Number(gms!.total).toLocaleString('ko-KR')}</small>}
              </label>
              <label>날짜
                <Input size="small" disabled={!canEdit} value={d.date} placeholder="YYYY-MM-DD"
                       onChange={(e) => onChange({ ...d, date: e.target.value })} aria-label={`No.${r.index + 1} 날짜`} />
              </label>
              <label className={needed.has('purpose') ? s.need : ''}>사용 목적
                <Select size="small" disabled={!canEdit} allowClear value={d.facts.purpose} placeholder="골라 주세요"
                        options={PURPOSES.map((p) => ({ value: p, label: p }))} onChange={(v) => setFact('purpose', v)}
                        aria-label={`No.${r.index + 1} 사용 목적`} />
              </label>
              <label className={needed.has('headcount') ? s.need : ''}>참석 인원
                <InputNumber size="small" disabled={!canEdit} min={1} max={999} value={d.facts.headcount ?? null} style={{ width: '100%' }}
                             onChange={(v) => setFact('headcount', v)} aria-label={`No.${r.index + 1} 참석 인원`} />
                <small className={s.muted}>{headHint(r)}</small>
              </label>
              <label className={needed.has('attendees') ? s.need : ''}>참석자
                <Input size="small" disabled={!canEdit} value={d.facts.attendees} placeholder="쉼표로 구분"
                       onChange={(e) => setFact('attendees', e.target.value)} aria-label={`No.${r.index + 1} 참석자`} />
              </label>
              <label className={`${s.rcWide} ${needed.has('reason') ? s.need : ''}`}>사용 사유 (야간·주말·공휴일)
                <Input size="small" disabled={!canEdit} value={d.facts.reason} maxLength={300}
                       onChange={(e) => setFact('reason', e.target.value)} aria-label={`No.${r.index + 1} 사용 사유`} />
              </label>
            </div>
          </div>
          {r.items.length > 0 && (
            <div className={s.rcItems}>
              {r.items.map((i, k) => (
                <span key={k}>{i.name}{(i.quantity ?? 1) > 1 ? ` ×${i.quantity}` : ''}{i.kind ? <em> {i.kind}</em> : null}</span>
              ))}
            </div>
          )}
          <div className={s.rcFoot}>
            <label className={s.rcSwitch}>
              <Switch size="small" disabled={!canEdit} checked={d.include} onChange={(v) => onChange({ ...d, include: v })} />
              정산에 포함
            </label>
            <Input size="small" disabled={!canEdit} value={d.note} maxLength={500} placeholder="결재자에게 남길 설명 (선택)"
                   onChange={(e) => onChange({ ...d, note: e.target.value })} aria-label={`No.${r.index + 1} 설명`} />
          </div>
        </div>
      )}
    </li>
  );
}

/**
 * 영수증 여러 장 사람 확인. 걸린 장을 먼저 펼쳐 보여 주고, 장마다 값·사실을 고치거나 정산에서 뺀다.
 * 규정 검토 뒤의 확인이면 [고친 장 다시 검토]로 판정을 미리 볼 수 있고, 확인하면 서버가 고친 장을 다시 검토한다.
 * 그래도 걸린 장은 결재 상신에서 장마다 따로 문서가 된다(결재자가 사유를 보고 판단).
 */
export function HitlReceipts({ runId, waiting, canResume, onResumed, documentId }:
  { runId: number; waiting: HitlWaiting; canResume: boolean; onResumed: () => void; documentId?: number }) {
  const receipts = useMemo(() => waiting.receipts ?? [], [waiting.receipts]);
  const resume = useResumeRun(runId);
  const previewApi = usePolicyPreview(runId);
  const notify = useNotify();
  const [drafts, setDrafts] = useState<Record<number, Draft>>(() => Object.fromEntries(receipts.map((r) => [r.index, initial(r)])));
  const [openSet, setOpenSet] = useState<Set<number>>(() => new Set(receipts.filter((r) => r.flagged).slice(0, 5).map((r) => r.index)));
  const [filter, setFilter] = useState<'flagged' | 'all'>(receipts.some((r) => r.flagged) ? 'flagged' : 'all');
  const [page, setPage] = useState(1);
  const [comment, setComment] = useState('');
  const [previews, setPreviews] = useState<Record<number, PolicyPreview>>({});
  const [file, setFile] = useState<PreviewTarget | null>(null);

  const flagged = receipts.filter((r) => r.flagged);
  const shownAll = filter === 'flagged' ? flagged : receipts;
  const shown = shownAll.slice((page - 1) * PAGE, page * PAGE);
  const answers = receipts.map((r) => answerOf(r, drafts[r.index])).filter((a): a is HitlReceiptAnswer => a != null);
  const changedPolicy = answers.filter((a) => (a.data || a.facts) && drafts[a.index].include);
  const emptyTotal = receipts.filter((r) => drafts[r.index].include && drafts[r.index].total == null);
  const included = receipts.filter((r) => drafts[r.index].include);
  const stillFlagged = included.filter((r) => (previews[r.index]?.verdict ?? (r.flagged ? 'x' : '준수')) !== '준수');
  const sum = included.reduce((acc, r) => acc + (drafts[r.index].total ?? 0), 0);

  const toggle = (i: number) => setOpenSet((st) => { const n = new Set(st); if (n.has(i)) n.delete(i); else n.add(i); return n; });
  const recheck = () => previewApi.mutate(changedPolicy, {
    onSuccess: (list) => {
      setPreviews((m) => ({ ...m, ...Object.fromEntries(list.map((p) => [p.index, p])) }));
      notify.success(`${list.length}장을 다시 검토했어요`, `준수 ${list.filter((p) => p.verdict === '준수').length}장`);
    },
    onError: (e) => notify.error(e, '다시 검토하지 못했습니다'),
  });
  const submit = (approved: boolean) => {
    if (!canResume || resume.isPending) return;
    if (approved && emptyTotal.length) {
      notify.info(`No.${emptyTotal.map((r) => r.index + 1).join(', ')}의 금액을 입력하거나 정산에서 빼 주세요`, '빈 금액으로는 계속할 수 없습니다');
      return;
    }
    resume.mutate({ approved, comment: comment.trim() || undefined, receipts: approved ? answers : undefined }, {
      onSuccess: () => { notify.success(approved ? '확인하고 계속 진행합니다' : '반려하고 종료합니다'); onResumed(); },
      onError: (e) => notify.error(e, '재개하지 못했습니다'),
    });
  };

  return (
    <div className={s.hitl}
         onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); submit(true); } }}>
      {!canResume && <Alert type="info" showIcon message={documentId ? '결재자 읽기 전용 화면입니다' : '요청자만 재개할 수 있습니다'} />}
      <div className={s.rcTop}>
        <div>
          <h3 className={s.h3}>{waiting.message}</h3>
          <span className={s.muted}>
            걸린 장 <b className="nx-num">{flagged.length}</b> / 전체 <span className="nx-num">{receipts.length}</span>장 ·
            정산 포함 <span className="nx-num">{included.length}</span>장 <span className="nx-num">{sum.toLocaleString('ko-KR')}</span>원
          </span>
        </div>
        <span className={s.spacer} />
        <Segmented<'flagged' | 'all'> size="small" value={filter} onChange={(v) => { setFilter(v); setPage(1); }}
                                      options={[{ label: `걸린 장 ${flagged.length}`, value: 'flagged' }, { label: `전체 ${receipts.length}`, value: 'all' }]} />
      </div>

      {shownAll.length === 0 ? <div className={s.muted}>걸린 장이 없어요. [전체]에서 모든 장을 볼 수 있어요.</div> : (
        <ul className={s.rcList}>
          {shown.map((r) => (
            <ReceiptCard key={r.index} r={r} d={drafts[r.index]} mode={waiting.mode} open={openSet.has(r.index)} canEdit={canResume}
                         preview={previews[r.index]} documentId={documentId} onToggle={() => toggle(r.index)} onPreview={setFile}
                         onChange={(d) => setDrafts((m) => ({ ...m, [r.index]: d }))} />
          ))}
        </ul>
      )}
      {shownAll.length > PAGE && <Pagination size="small" current={page} pageSize={PAGE} total={shownAll.length} onChange={setPage} showSizeChanger={false} />}

      {canResume && (
        <>
          {waiting.mode === 'policy' && (
            <div className={s.formFoot}>
              <Button size="small" disabled={changedPolicy.length === 0} loading={previewApi.isPending} onClick={recheck}>
                고친 장 다시 검토{changedPolicy.length ? ` (${changedPolicy.length})` : ''}
              </Button>
              <span className={s.muted}>
                {stillFlagged.length
                  ? `걸린 장 ${stillFlagged.length}장은 결재 문서가 장마다 따로 올라가요 (설명을 남기면 결재자가 같이 봐요)`
                  : '준수한 장은 계정과목·월별로 묶어서 결재가 올라가요'}
              </span>
            </div>
          )}
          <Input.TextArea value={comment} onChange={(e) => setComment(e.target.value)} maxLength={500}
                          autoSize={{ minRows: 1, maxRows: 4 }} placeholder="코멘트(선택)" aria-label="코멘트" />
          <div className={s.formFoot}>
            <Button danger onClick={() => submit(false)} disabled={resume.isPending}>반려하고 종료</Button>
            <span className={s.spacer} />
            <span className={s.muted}>{answers.length ? `${answers.length}장 고침` : ''}</span>
            <Button type="primary" loading={resume.isPending} onClick={() => submit(true)}>
              확인하고 계속 <Kbd onPrimary>{modKey}</Kbd>+<Kbd onPrimary>Enter</Kbd>
            </Button>
          </div>
        </>
      )}
      <FilePreview target={file} onClose={() => setFile(null)} documentId={documentId} />
    </div>
  );
}
