import { useMemo, useState } from 'react';
import { Input, InputNumber, Pagination, Select } from 'antd';
import s from './automation.module.css';

/** 경비 사용 목적 (FastAPI ai/rag/policy.py PURPOSES와 같은 값 — 서버가 폼 options로도 내려 준다) */
export const PURPOSES = ['야근', '주말근무', '회식', '회의', '접대', '교통', '소모품', '기타'];

export interface ReceiptFactRow { purpose?: string; headcount?: number | null; attendees?: string; reason?: string }
export interface ReceiptFile { uid: string; name: string; fileId?: number }

const PAGE = 20;

/** 표의 값 → 실행 입력 inputs.receipts (빈 칸은 공통 값, 그것도 없으면 보내지 않음 → 서버가 '확인 필요'로 물어봄) */
export function receiptRows(files: ReceiptFile[], rows: Record<string, ReceiptFactRow>, common: ReceiptFactRow) {
  const out = files.map((f, index) => {
    const r = rows[f.uid] ?? {};
    const v: Record<string, unknown> = { fileId: f.fileId, name: f.name, index };
    const purpose = r.purpose || common.purpose;
    const attendees = (r.attendees || common.attendees || '').trim();
    if (purpose) v.purpose = purpose;
    if (r.headcount != null) v.headcount = r.headcount;
    if (attendees) v.attendees = attendees;
    if (r.reason?.trim()) v.reason = r.reason.trim();
    return v;
  });
  return out.some((v) => Object.keys(v).length > 3) ? out : undefined;
}

/**
 * 영수증별 정보 표: 올린 파일 한 장에 한 줄(사용 목적·참석 인원·참석자·사용 사유).
 * 위 '모든 장 공통' 값이 빈 칸을 채운다. 인원은 영수증에 찍혀 있으면 비워도 되고(서버가 읽음),
 * 빠진 정보는 규정 검토 뒤 확인 단계에서 장마다 다시 물어본다(품목으로 본 인원 범위도 그때 보여 준다).
 */
export function ReceiptFactsTable({ files, purposes, rows, common, onRows, onCommon }: {
  files: ReceiptFile[]; purposes: string[];
  rows: Record<string, ReceiptFactRow>; common: ReceiptFactRow;
  onRows: (next: Record<string, ReceiptFactRow>) => void; onCommon: (next: ReceiptFactRow) => void;
}) {
  const [page, setPage] = useState(1);
  const options = useMemo(() => (purposes.length ? purposes : PURPOSES).map((p) => ({ value: p, label: p })), [purposes]);
  const set = (uid: string, patch: ReceiptFactRow) => onRows({ ...rows, [uid]: { ...rows[uid], ...patch } });
  const shown = files.slice((page - 1) * PAGE, page * PAGE);

  return (
    <div className={s.rf}>
      <div className={s.rfCommon}>
        <b>모든 장 공통</b>
        <Select allowClear placeholder="사용 목적" value={common.purpose} options={options} style={{ width: 132 }}
                onChange={(v) => onCommon({ ...common, purpose: v })} aria-label="모든 장 공통 사용 목적" />
        <Input placeholder="참석자 (쉼표로 구분)" value={common.attendees} maxLength={300} style={{ width: 260 }}
               onChange={(e) => onCommon({ ...common, attendees: e.target.value })} aria-label="모든 장 공통 참석자" />
      </div>
      {files.length === 0 ? (
        <p className={s.muted}>파일을 올리면 장마다 사용 목적·인원을 적을 수 있어요. 비워 두어도 실행할 수 있어요.</p>
      ) : (
        <>
          <div className={s.rfScroll}>
            <table className={s.rfTable}>
              <thead>
                <tr><th>#</th><th>파일</th><th>사용 목적</th><th>참석 인원</th><th>참석자</th><th title="야간·주말·공휴일에 쓴 경우">사용 사유</th></tr>
              </thead>
              <tbody>
                {shown.map((f, i) => {
                  const r = rows[f.uid] ?? {};
                  const no = (page - 1) * PAGE + i + 1;
                  return (
                    <tr key={f.uid}>
                      <td className={s.rfNo}>{no}</td>
                      <td className={s.rfName} title={f.name}>{f.name}</td>
                      <td><Select size="small" allowClear placeholder={common.purpose ?? '목적'} value={r.purpose} options={options}
                                  style={{ width: 100 }} onChange={(v) => set(f.uid, { purpose: v })} aria-label={`${no}번 사용 목적`} /></td>
                      <td><InputNumber size="small" min={1} max={999} placeholder="자동" value={r.headcount ?? null} style={{ width: 64 }}
                                       onChange={(v) => set(f.uid, { headcount: v })} aria-label={`${no}번 참석 인원`} /></td>
                      <td><Input size="small" placeholder={common.attendees || '쉼표로 구분'} value={r.attendees} maxLength={300}
                                 onChange={(e) => set(f.uid, { attendees: e.target.value })} aria-label={`${no}번 참석자`} /></td>
                      <td><Input size="small" placeholder="야간·주말·공휴일만" value={r.reason} maxLength={300}
                                 onChange={(e) => set(f.uid, { reason: e.target.value })} aria-label={`${no}번 사용 사유`} /></td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          {files.length > PAGE && (
            <Pagination size="small" current={page} pageSize={PAGE} total={files.length} onChange={setPage} showSizeChanger={false} />
          )}
          <p className={s.muted}>
            빈 칸은 공통 값을 써요. 참석 인원은 영수증에 찍혀 있으면 비워도 되고, 모자란 정보는 검토 뒤 확인 단계에서 장마다 물어봐요.
          </p>
        </>
      )}
    </div>
  );
}
