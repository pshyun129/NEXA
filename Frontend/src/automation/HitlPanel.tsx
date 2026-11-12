import { useMemo, useState } from 'react';
import { Alert, Button, Input, InputNumber } from 'antd';
import { useResumeRun, type HitlWaiting } from '@/api/ai';
import { useNotify } from '@/ui/useNotify';
import { AiMark, Kbd, modKey } from '@/ui/primitives';
import { Icon } from '@/ui/Icon';
import { TriangleAlert, Check, Pencil } from '@/ui/icons';
import { fieldLabel, NUMERIC_FIELDS } from './fieldLabels';
import { HitlReceipts } from './HitlReceipts';
import s from './automation.module.css';

const norm = (v: unknown) => String(v ?? '').replace(/\s+/g, '');
const show = (k: string, v: unknown) =>
  v == null || v === '' ? '—' : NUMERIC_FIELDS.has(k) && typeof v === 'number' ? v.toLocaleString('ko-KR') : String(v);

const MISMATCH = /^GMS와 불일치:\s*(\S+)/;

/**
 * 사람 확인(설계 6.5 HitlFieldDiff). 불일치 칸만 강조하고, 추출값을 누르면 확정값으로 복사한다.
 * resume.data에는 고친 필드만 담는다. 요청자가 아니면 읽기 전용.
 */
export function HitlPanel(props: { runId: number; waiting: HitlWaiting; canResume: boolean; onResumed: () => void; documentId?: number }) {
  // 영수증 여러 장이면 장별 확인 화면 (한 장이면 예전 칸별 비교 화면)
  if ((props.waiting.receipts?.length ?? 0) > 1) return <HitlReceipts {...props} />;
  return <SingleHitl {...props} />;
}

function SingleHitl({ runId, waiting, canResume, onResumed }:
  { runId: number; waiting: HitlWaiting; canResume: boolean; onResumed: () => void }) {
  const resume = useResumeRun(runId);
  const notify = useNotify();
  const ai = waiting.fields ?? {};
  const gms = waiting.gms_fields;
  const keys = Object.keys(ai);

  // 불일치: reasons의 "GMS와 불일치: key" + 클라이언트 공백 정규화 비교(설계 6.5)
  const mismatch = useMemo(() => {
    const set = new Set<string>();
    for (const r of waiting.reasons) { const m = r.match(MISMATCH); if (m) set.add(m[1]); }
    if (gms) for (const k of keys) if (k in gms && norm(gms[k]) !== norm(ai[k])) set.add(k);
    return set;
  }, [waiting.reasons, gms, keys, ai]);

  const rows = [...keys].sort((a, b) => Number(mismatch.has(b)) - Number(mismatch.has(a)));
  const [values, setValues] = useState<Record<string, unknown>>(() => ({ ...ai }));
  const [comment, setComment] = useState('');
  const edited = rows.filter((k) => norm(values[k]) !== norm(ai[k]));

  // 숫자 칸을 비우면 화면은 비어 보이는데 서버에는 ""가 저장돼 규정 검토가 보류로 끝났다(QA AUTO-C1) → 확인 전에 막는다
  const emptyNumbers = rows.filter((k) => NUMERIC_FIELDS.has(k) && (values[k] === '' || values[k] == null));

  const submit = (approved: boolean) => {
    if (!canResume || resume.isPending) return;
    if (approved && emptyNumbers.length) {
      notify.info(`${emptyNumbers.map((k) => fieldLabel(k)).join(', ')}을(를) 입력해 주세요`, '빈 칸으로는 계속할 수 없습니다');
      return;
    }
    const data: Record<string, unknown> = {};
    for (const k of edited) {
      const v = values[k];
      data[k] = NUMERIC_FIELDS.has(k) && v !== '' && v != null ? Number(v) : v;
    }
    resume.mutate(
      { approved, comment: comment.trim() || undefined, data: approved && edited.length ? data : undefined },
      {
        onSuccess: () => { notify.success(approved ? '확인하고 계속 진행합니다' : '반려하고 종료합니다'); onResumed(); },
        onError: (e) => notify.error(e, '재개하지 못했습니다'),
      },
    );
  };

  const set = (k: string, v: unknown) => setValues((m) => ({ ...m, [k]: v }));
  const otherReasons = waiting.reasons.filter((r) => !MISMATCH.test(r));

  return (
    <div className={s.hitl}
         onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); submit(true); } }}>
      {!canResume && <Alert type="info" showIcon message="요청자만 재개할 수 있습니다" />}
      <div>
        <h3 className={s.h3}>확인이 필요한 이유</h3>
        {waiting.reasons.length === 0
          ? <div className={s.muted}>{waiting.message}</div>
          : <ul className={s.reasons}>{[...waiting.reasons.filter((r) => MISMATCH.test(r)), ...otherReasons].map((r) => <li key={r}>{r}</li>)}</ul>}
      </div>

      <table className={s.diff}>
        <thead>
          <tr>
            <th>상태</th><th>필드</th><th><AiMark label="AI 추출" /></th>
            {gms && <th>교차 추출</th>}
            <th>확정값</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((k) => {
            const bad = mismatch.has(k);
            const changed = norm(values[k]) !== norm(ai[k]);
            const copy = (v: unknown) => canResume && set(k, v);
            return (
              <tr key={k} className={bad ? s.rowBad : s.rowOk}>
                <td>
                  {changed ? <span className={s.stEdited}><Icon as={Pencil} size={12} /> 직접 수정</span>
                    : bad ? <span className={s.stBad}><Icon as={TriangleAlert} size={12} /> 확인 필요</span>
                    : <span className={s.stOk}><Icon as={Check} size={12} /> 자동 확정</span>}
                </td>
                <th scope="row">{fieldLabel(k)}</th>
                <td><button type="button" className={s.cellBtn} disabled={!canResume} onClick={() => copy(ai[k])}
                            title="확정값으로 복사">{show(k, ai[k])}</button></td>
                {gms && (
                  <td><button type="button" className={s.cellBtn} disabled={!canResume} onClick={() => copy(gms[k])}
                              title="확정값으로 복사">{show(k, gms[k])}</button></td>
                )}
                <td>
                  {NUMERIC_FIELDS.has(k)
                    ? <InputNumber aria-label={`${fieldLabel(k)} 확정값`} disabled={!canResume} style={{ width: 160 }}
                                   status={emptyNumbers.includes(k) ? 'error' : undefined}
                                   value={values[k] === '' || values[k] == null ? null : Number(values[k])}
                                   // 지운 칸이 "0"으로 보이지 않게 빈 값은 빈 칸으로 둔다(QA AUTO-C1)
                                   formatter={(v) => (v == null || String(v) === '' ? '' : Number(v).toLocaleString('ko-KR'))}
                                   parser={(v) => (v ? Number(v.replace(/[^\d.-]/g, '')) : '') as unknown as number}
                                   onChange={(v) => set(k, v ?? '')} />
                    : <Input aria-label={`${fieldLabel(k)} 확정값`} disabled={!canResume} style={{ width: 200 }}
                             value={String(values[k] ?? '')} onChange={(e) => set(k, e.target.value)} />}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <div className={s.muted}>
        불일치 {mismatch.size} · 일치 {rows.length - mismatch.size}
        {edited.length > 0 && <> (그중 직접 수정 {edited.length})</>}
        {canResume && <> · 추출값을 누르면 확정값으로 복사됩니다</>}
      </div>

      {canResume && (
        <>
          <Input.TextArea value={comment} onChange={(e) => setComment(e.target.value)} maxLength={500}
                          autoSize={{ minRows: 1, maxRows: 4 }} placeholder="코멘트(선택)" aria-label="코멘트" />
          <div className={s.formFoot}>
            <Button danger onClick={() => submit(false)} disabled={resume.isPending}>반려하고 종료</Button>
            <span className={s.spacer} />
            <Button type="primary" loading={resume.isPending} onClick={() => submit(true)}>
              확인하고 계속 <Kbd onPrimary>{modKey}</Kbd>+<Kbd onPrimary>Enter</Kbd>
            </Button>
          </div>
        </>
      )}
    </div>
  );
}
