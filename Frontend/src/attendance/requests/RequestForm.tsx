import { useEffect, useMemo, useRef, useState } from 'react';
import { Button, DatePicker, Input, Segmented, Select, Skeleton } from 'antd';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import dayjs from 'dayjs';
import {
  usePreviewRequest, useSubmitRequest, useDayLogs,
  type AttendanceRequestType, type RequestInput, type RequestItemInput, type WorkStatusLog,
} from '@/api/attendanceRequests';
import { Card, Kbd, modKey } from '@/ui/primitives';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { useNotify } from '@/ui/useNotify';
import { Icon } from '@/ui/Icon';
import { Lock, X } from '@/ui/icons';
import { formatDate, formatTime, nowKst } from '@/format';
import { requestTypeLabel, workStatusLabel, workStatusSourceLabel } from '@/status/labels';
import { PreviewResult, RequestError } from './PreviewResult';
import { TimeField, dateParam, emptyTime, timeFromIso, timeToIso, type TimeVal } from './TimeField';
import { REQUESTS_PATH, requestHref } from './links';
import s from './requests.module.css';

const TYPES: AttendanceRequestType[] = ['TIME_FIX', 'OVERTIME', 'HOLIDAY_SUBST'];
/** 근무시간 수정에서 고를 수 있는 상태(휴가·퇴근취소는 서버가 거부) */
const FIX_CODES = ['CLOCK_IN', 'WORKING', 'OFF_WORKING', 'CLOCK_OUT'];
const REASON_MAX = 500;

let seq = 0;
const nextKey = () => `r${++seq}`;

type FixRow =
  | { key: string; kind: 'log'; log: WorkStatusLog; mode: 'keep' | 'modify' | 'delete'; statusCode: string; at: TimeVal }
  | { key: string; kind: 'add'; statusCode: string; at: TimeVal };
interface OtRow { key: string; start: TimeVal; end: TimeVal }

const today = () => nowKst().format('YYYY-MM-DD');

/* ---------- 근무시간 수정 ---------- */
function TimeFixRows({ date, rows, setRows, fillClock }:
  { date: string; rows: FixRow[]; setRows: (r: FixRow[]) => void; fillClock: boolean }) {
  const logs = useDayLogs(date);
  const initFor = useRef<string | null>(null);

  // 그날 기록이 오면 한 번만 행을 만든다. MISSING_CLOCK 딥링크면 없는 출근·퇴근을 빈 추가 행으로 넣는다
  useEffect(() => {
    if (!logs.data || initFor.current === date) return;
    initFor.current = date;
    const base: FixRow[] = logs.data.map((log) => ({
      key: nextKey(), kind: 'log', log, mode: 'keep', statusCode: log.statusCode, at: timeFromIso(date, log.startAt),
    }));
    if (fillClock) {
      for (const code of ['CLOCK_IN', 'CLOCK_OUT']) {
        if (!logs.data.some((l) => l.statusCode === code)) base.push({ key: nextKey(), kind: 'add', statusCode: code, at: emptyTime });
      }
    }
    setRows(base);
  }, [logs.data, date, fillClock, setRows]);

  if (logs.isError) return <ErrorPanel error={logs.error} onRetry={() => logs.refetch()} />;
  if (!logs.data || initFor.current !== date) return <Skeleton active={false} paragraph={{ rows: 3 }} />;

  const update = (key: string, patch: Partial<FixRow>) =>
    setRows(rows.map((r) => (r.key === key ? ({ ...r, ...patch } as FixRow) : r)));
  const codeOptions = FIX_CODES.map((c) => ({ value: c, label: workStatusLabel(c) }));

  return (
    <div className={s.rows}>
      {rows.length === 0 && <div className={s.muted}>이날 기록이 없습니다. 아래에서 출근·퇴근을 추가해 주세요.</div>}
      {rows.map((r) => {
        if (r.kind === 'log') {
          const locked = r.log.statusCode === 'CLOCK_OUT_CANCEL';
          const editing = r.mode === 'modify';
          return (
            <div key={r.key} className={[s.row, r.mode === 'delete' ? s.rowDeleted : ''].join(' ')}>
              <span className={`${s.rowOrig} nx-num`}>
                {formatTime(r.log.startAt)} {r.log.statusName}
                <span className={s.muted}> · {workStatusSourceLabel(r.log.source)}</span>
              </span>
              {locked ? (
                <span className={s.muted}><Icon as={Lock} size={12} /> 퇴근취소 기록은 바꿀 수 없습니다</span>
              ) : (
                <>
                  <Segmented
                    size="small"
                    value={r.mode}
                    onChange={(v) => update(r.key, { mode: v as 'keep' | 'modify' | 'delete' })}
                    options={[{ value: 'keep', label: '유지' }, { value: 'modify', label: '수정' }, { value: 'delete', label: '삭제' }]}
                  />
                  {editing && (
                    <>
                      <Select size="small" style={{ width: 104 }} value={r.statusCode} options={codeOptions}
                              aria-label="상태" onChange={(v) => update(r.key, { statusCode: v })} />
                      <TimeField label="시각" value={r.at} onChange={(at) => update(r.key, { at })} />
                    </>
                  )}
                </>
              )}
            </div>
          );
        }
        return (
          <div key={r.key} className={`${s.row} ${s.rowAdded}`}>
            <span className={s.rowOrig}>추가</span>
            <Select size="small" style={{ width: 104 }} value={r.statusCode} options={codeOptions}
                    aria-label="추가할 상태" onChange={(v) => update(r.key, { statusCode: v })} />
            <TimeField label="추가할 시각" value={r.at} onChange={(at) => update(r.key, { at })} />
            <Button size="small" type="text" aria-label="추가 항목 삭제" icon={<Icon as={X} />}
                    onClick={() => setRows(rows.filter((x) => x.key !== r.key))} />
          </div>
        );
      })}
      <div>
        <Button size="small" onClick={() => setRows([...rows, { key: nextKey(), kind: 'add', statusCode: 'CLOCK_IN', at: emptyTime }])}>
          + 기록 추가
        </Button>
      </div>
    </div>
  );
}

/* ---------- 초과근무 ---------- */
function OvertimeRows({ rows, setRows }: { rows: OtRow[]; setRows: (r: OtRow[]) => void }) {
  const update = (key: string, patch: Partial<OtRow>) => setRows(rows.map((r) => (r.key === key ? { ...r, ...patch } : r)));
  return (
    <div className={s.rows}>
      {rows.map((r, i) => (
        <div key={r.key} className={s.row}>
          <span className={s.rowOrig}>구간 {i + 1}</span>
          <TimeField label={`구간 ${i + 1} 시작`} value={r.start} onChange={(start) => update(r.key, { start })} />
          <span>~</span>
          <TimeField label={`구간 ${i + 1} 종료`} value={r.end} onChange={(end) => update(r.key, { end })} />
          {rows.length > 1 && (
            <Button size="small" type="text" aria-label={`구간 ${i + 1} 삭제`} icon={<Icon as={X} />}
                    onClick={() => setRows(rows.filter((x) => x.key !== r.key))} />
          )}
        </div>
      ))}
      <div>
        <Button size="small" onClick={() => setRows([...rows, { key: nextKey(), start: emptyTime, end: emptyTime }])}>+ 구간 추가</Button>
      </div>
    </div>
  );
}

/* ---------- 입력 → 요청 ---------- */
function buildInput(type: AttendanceRequestType, date: string | null, subst: string | null,
  fix: FixRow[], ot: OtRow[]): RequestInput | string {
  if (!date) return '대상일을 골라 주세요.';
  if (type === 'HOLIDAY_SUBST') {
    if (!subst) return '대신 쉴 날을 골라 주세요.';
    return { reqType: type, targetDate: date, substDate: subst };
  }
  if (type === 'OVERTIME') {
    const items: RequestItemInput[] = [];
    for (const r of ot) {
      const startAt = timeToIso(date, r.start);
      const endAt = timeToIso(date, r.end);
      if (!startAt || !endAt) return '초과근무 구간의 시작·종료 시각을 넣어 주세요.';
      items.push({ statusCode: 'WORKING', startAt, endAt });
    }
    return { reqType: type, targetDate: date, items };
  }
  if (date > today()) return '미래 날짜의 근무시간은 수정할 수 없습니다.';
  const items: RequestItemInput[] = [];
  for (const r of fix) {
    if (r.kind === 'log') {
      if (r.mode === 'delete') items.push({ action: 'DELETE', targetLogId: r.log.logId });
      if (r.mode === 'modify') {
        const startAt = timeToIso(date, r.at);
        if (!startAt) return '수정할 기록의 시각을 넣어 주세요.';
        items.push({ action: 'MODIFY', targetLogId: r.log.logId, statusCode: r.statusCode, startAt });
      }
    } else {
      const startAt = timeToIso(date, r.at);
      if (!startAt) return '추가할 기록의 시각을 넣어 주세요.';
      items.push({ action: 'ADD', statusCode: r.statusCode, startAt });
    }
  }
  if (items.length === 0) return '바꿀 기록을 하나 이상 골라 주세요.';
  return { reqType: type, targetDate: date, items };
}

/** 근태 신청(요구사항 3.3): 유형 → 입력 → 확인(preview) → 사유 → 상신 → 상세 */
export function RequestForm() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const notify = useNotify();
  const initType = (TYPES as string[]).includes(params.get('type') ?? '') ? (params.get('type') as AttendanceRequestType) : 'TIME_FIX';

  const [type, setType] = useState<AttendanceRequestType>(initType);
  const [date, setDate] = useState<string | null>(dateParam(params.get('date')) ?? (initType === 'HOLIDAY_SUBST' ? null : today()));
  const [subst, setSubst] = useState<string | null>(dateParam(params.get('subst')));
  const [fix, setFix] = useState<FixRow[]>([]);
  const [ot, setOt] = useState<OtRow[]>(() => {
    const d = dateParam(params.get('date'));
    const starts = params.getAll('start');
    const ends = params.getAll('end');
    // 미승인 구간이 여러 개면 구간마다 한 행(start·end는 같은 순서로 짝지음)
    if (d && starts.length > 0) {
      return starts.map((st, i) => ({ key: nextKey(), start: timeFromIso(d, st), end: timeFromIso(d, ends[i]) }));
    }
    return [{ key: nextKey(), start: emptyTime, end: emptyTime }];
  });
  const [reason, setReason] = useState('');
  const fillClock = params.get('fill') === 'clock' && date === dateParam(params.get('date'));

  const preview = usePreviewRequest();
  const submit = useSubmitRequest();

  const input = useMemo(() => buildInput(type, date, subst, fix, ot), [type, date, subst, fix, ot]);
  const inputKey = typeof input === 'string' ? null : JSON.stringify(input);
  const [checkedKey, setCheckedKey] = useState<string | null>(null);
  // 입력이 바뀌면 이전 확인 결과는 쓰지 않는다
  const fresh = inputKey != null && checkedKey === inputKey;
  const previewOk = fresh && preview.isSuccess;
  const previewErr = fresh && preview.isError;

  const check = () => {
    if (typeof input === 'string') return;
    setCheckedKey(JSON.stringify(input));
    preview.mutate(input);
  };

  const reasonOk = reason.trim().length > 0 && reason.length <= REASON_MAX;
  const canSubmit = previewOk && reasonOk && !submit.isPending;
  const doSubmit = () => {
    if (!canSubmit || typeof input === 'string') return;
    submit.mutate({ ...input, reason: reason.trim() }, {
      onSuccess: (res) => {
        notify.success('근태 신청을 상신했습니다', res.docNo ? `결재 문서 ${res.docNo}` : undefined);
        navigate(requestHref(res.attReqId), { replace: true });
      },
    });
  };

  const changeType = (t: AttendanceRequestType) => {
    setType(t);
    if (t === 'TIME_FIX' && date && date > today()) setDate(today());
  };
  const changeDate = (d: string | null) => { setDate(d); setFix([]); };

  return (
    <div className={s.formGrid}>
      <Card title="근태 신청" meta={<Link to={REQUESTS_PATH}>← 신청 목록</Link>}>
        <div className={s.form}>
          <div className={s.field}>
            <span className={s.label}>유형</span>
            <Segmented<AttendanceRequestType>
              value={type}
              onChange={changeType}
              options={TYPES.map((t) => ({ value: t, label: requestTypeLabel(t) }))}
            />
          </div>

          <div className={s.field}>
            <span className={s.label}>{type === 'HOLIDAY_SUBST' ? '근무한 휴일' : '대상일'}</span>
            <DatePicker
              allowClear={false}
              aria-label="대상일"
              value={date ? dayjs(date) : null}
              disabledDate={type === 'TIME_FIX' ? (d) => d.format('YYYY-MM-DD') > today() : undefined}
              onChange={(v) => changeDate(v ? v.format('YYYY-MM-DD') : null)}
            />
            {date && <span className={s.muted}>{formatDate(date)}</span>}
          </div>

          {type === 'HOLIDAY_SUBST' && (
            <div className={s.field}>
              <span className={s.label}>대신 쉴 날</span>
              <DatePicker aria-label="대신 쉴 날" value={subst ? dayjs(subst) : null}
                          onChange={(v) => setSubst(v ? v.format('YYYY-MM-DD') : null)} />
              <span className={s.muted}>근무일이어야 하고, 휴가가 없는 날이어야 합니다</span>
            </div>
          )}

          {type === 'TIME_FIX' && date && (
            <div className={s.fieldCol}>
              <span className={s.label}>기록</span>
              <TimeFixRows key={date} date={date} rows={fix} setRows={setFix} fillClock={fillClock} />
              <span className={s.muted}>시각은 근무일 기준입니다. 자정을 넘긴 퇴근은 "다음 날"을 체크해 주세요.</span>
            </div>
          )}
          {type === 'OVERTIME' && (
            <div className={s.fieldCol}>
              <span className={s.label}>초과근무 구간</span>
              <OvertimeRows rows={ot} setRows={setOt} />
            </div>
          )}

          <div className={s.checkRow}>
            {typeof input === 'string' && <span className={s.muted}>{input}</span>}
            <span className={s.spacer} />
            <Button onClick={check} disabled={typeof input === 'string'} loading={preview.isPending}>
              {previewOk ? '다시 확인' : '확인'}
            </Button>
          </div>
        </div>
      </Card>

      <Card title="확인 결과" meta={previewOk ? <span className={s.ok}>확인됨</span> : undefined}>
        <div className={s.form}>
          {!fresh && <div className={s.muted}>입력을 마치고 [확인]을 누르면 반영 결과를 미리 보여 줍니다. 입력을 바꾸면 다시 확인해야 합니다.</div>}
          {previewErr && <RequestError error={preview.error} />}
          {previewOk && preview.data && <PreviewResult data={preview.data} />}

          <div className={s.fieldCol}>
            <span className={s.label}>사유 (필수)</span>
            <Input.TextArea
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              maxLength={REASON_MAX}
              showCount
              autoSize={{ minRows: 3, maxRows: 8 }}
              aria-label="신청 사유"
              placeholder="신청 사유를 적어 주세요"
              onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); doSubmit(); } }}
            />
          </div>
          {submit.isError && <RequestError error={submit.error} />}
          <div className={s.checkRow}>
            <span className={s.muted}>결재선은 규정에 따라 자동으로 정해집니다</span>
            <span className={s.spacer} />
            <Button onClick={() => navigate(REQUESTS_PATH)} disabled={submit.isPending}>취소</Button>
            <Button type="primary" disabled={!canSubmit} loading={submit.isPending} onClick={doSubmit}>
              상신 <Kbd onPrimary>{modKey}</Kbd>+<Kbd onPrimary>Enter</Kbd>
            </Button>
          </div>
          {!previewOk && <div className={s.muted}>상신하려면 먼저 [확인]을 통과해야 합니다.</div>}
        </div>
      </Card>
    </div>
  );
}
