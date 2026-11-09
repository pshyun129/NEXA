import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Input, Segmented, Select, Skeleton } from 'antd';
import { Link, useNavigate } from 'react-router-dom';
import { isApiError } from '@/api/ApiError';
import { useHolidays } from '@/api/attendance';
import {
  leaveDay, useLeavePreview, useLeaveRequestDetails, useLeaveTypes, useMyLeaveRequests, useSubmitLeave,
  type AllowedUnits, type LeaveInput, type LeavePortion, type LeaveSubmission,
} from '@/api/leave';
import { Card, Kbd, modKey } from '@/ui/primitives';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { Icon } from '@/ui/Icon';
import { ChevronLeft, ChevronRight, X } from '@/ui/icons';
import { StatusBadge } from '@/ui/StatusBadge';
import { useNotify } from '@/ui/useNotify';
import { formatDate, formatLeaveDays, kst, nowKst } from '@/format';
import { leavePortionLabel } from '@/status/labels';
import { NoPolicyNotice } from './Balances';
import { LEAVE_PATH, leaveRequestHref } from './links';
import s from './leave.module.css';

const WEEKDAYS = ['월', '화', '수', '목', '금', '토', '일'];
const REASON_MAX = 2000;
const MAX_DATES = 366;
/** 날짜 선택이 바뀐 뒤 preview를 부르기까지(설계 6.3) */
const DEBOUNCE_MS = 300;
/** 기신청 표시에 쓰는 진행·확정 상태 */
const BOOKED = new Set(['SUBMITTING', 'SUBMITTED', 'APPROVED']);

const PORTIONS: Record<AllowedUnits, LeavePortion[]> = {
  FULL: ['FULL'],
  HALF: ['AM_HALF', 'PM_HALF'],
  // 시간 단위 신청은 서버가 아직 받지 않는다(HOURLY 거부). 고를 수 있는 단위는 preview가 다시 검사한다
  HOUR: ['FULL', 'AM_HALF', 'PM_HALF'],
  MIXED: ['FULL', 'AM_HALF', 'PM_HALF'],
};
const SHORT: Record<LeavePortion, string> = { FULL: '종일', AM_HALF: '오전', PM_HALF: '오후' };

function useDebounced<T>(value: T, ms: number) {
  const [v, setV] = useState(value);
  useEffect(() => {
    const t = window.setTimeout(() => setV(value), ms);
    return () => window.clearTimeout(t);
  }, [value, ms]);
  return v;
}

/** 이미 신청(결재 중·승인)한 날짜. 목록에 날짜가 없어 진행 중인 신청만 상세를 읽는다 */
function useBookedDates() {
  const list = useMyLeaveRequests();
  const ids = (list.data ?? []).filter((r) => BOOKED.has(r.status)).slice(0, 20).map((r) => r.requestId);
  const details = useLeaveRequestDetails(ids);
  const m = new Map<string, string>();
  for (const d of details) {
    for (const x of d.data?.dates ?? []) m.set(leaveDay(x.leaveDate), leavePortionLabel(x.portion));
  }
  return m;
}

function Calendar({ month, setMonth, selected, toggle, booked, errors }: {
  month: string; setMonth: (m: string) => void;
  selected: Map<string, LeavePortion>; toggle: (d: string) => void;
  booked: Map<string, string>; errors: Map<string, string>;
}) {
  const first = kst(`${month}-01`);
  const last = first.endOf('month');
  const today = nowKst().format('YYYY-MM-DD');
  const holidays = useHolidays(first.format('YYYY-MM-DD'), last.format('YYYY-MM-DD'));
  const holidayName = new Map((holidays.data ?? []).map((h) => [h.holidayDate, h.name]));
  const lead = first.isoWeekday() - 1;
  const cells = Array.from({ length: last.date() }, (_, i) => first.add(i, 'day'));
  const thisMonth = nowKst().format('YYYY-MM');

  return (
    <div>
      <div className={s.monthHead}>
        <Button size="small" icon={<Icon as={ChevronLeft} />} aria-label="이전 달" disabled={month <= thisMonth}
                onClick={() => setMonth(first.subtract(1, 'month').format('YYYY-MM'))} />
        <span className={`${s.monthLabel} nx-num`}>{first.format('YYYY년 M월')}</span>
        <Button size="small" icon={<Icon as={ChevronRight} />} aria-label="다음 달"
                onClick={() => setMonth(first.add(1, 'month').format('YYYY-MM'))} />
      </div>
      <div className={s.cal} role="group" aria-label={`${first.format('YYYY년 M월')} 휴가 날짜 선택`}>
        {WEEKDAYS.map((w) => <span key={w} className={s.calWeekday}>{w}</span>)}
        {Array.from({ length: lead }, (_, i) => <span key={`b${i}`} />)}
        {cells.map((d) => {
          const key = d.format('YYYY-MM-DD');
          const off = d.isoWeekday() >= 6 || holidayName.has(key);
          const past = key < today;
          const sel = selected.get(key);
          const bookedAs = booked.get(key);
          const err = errors.get(key);
          const hint = sel ? SHORT[sel] : holidayName.get(key) ?? (bookedAs ? '신청됨' : '');
          const aria = [formatDate(key), holidayName.get(key), bookedAs ? `이미 신청(${bookedAs})` : null,
            sel ? `선택됨 ${leavePortionLabel(sel)}` : null, err].filter(Boolean).join(', ');
          return (
            <button
              key={key}
              type="button"
              disabled={past}
              aria-pressed={!!sel}
              aria-label={aria}
              title={err ?? holidayName.get(key)}
              onClick={() => toggle(key)}
              className={[s.day, off ? s.dayOff : '', key === today ? s.dayToday : '', sel ? s.daySelected : ''].join(' ')}
            >
              {err && <span className={s.dayError} aria-hidden>!</span>}
              {bookedAs && <span className={s.dayBooked} aria-hidden />}
              <span className={`${s.dayNum} nx-num`}>{d.date()}</span>
              {hint && <span className={s.dayHint}>{hint}</span>}
            </button>
          );
        })}
      </div>
      <div className={s.legend}>
        <span><i className={s.swatch} style={{ background: 'var(--nx-brand-600)' }} />선택</span>
        <span><i className={s.swatch} style={{ background: 'var(--nx-info-fg)', borderRadius: '50%' }} />기신청</span>
        <span><b style={{ color: 'var(--nx-danger-fg)' }}>!</b> 확인 필요</span>
        <span><i className={s.swatch} style={{ background: 'var(--nx-n-50)', border: '1px solid var(--nx-border)' }} />주말·공휴일</span>
      </div>
    </div>
  );
}

/** 202(처리 확인 중)·200(기존 결과) 응답. 201이면 상세로 넘어가므로 여기 오지 않는다 */
function SubmissionNotice({ res, retry, retrying }: { res: LeaveSubmission; retry: () => void; retrying: boolean }) {
  if (res.status === 'SUBMITTING') {
    return (
      <Alert
        type="warning"
        showIcon
        message={<span className={s.head}><StatusBadge domain="LeaveRequestStatus" value="SUBMITTING" /> {res.message}</span>}
        action={<Button size="small" loading={retrying} onClick={retry}>같은 요청으로 다시 확인</Button>}
      />
    );
  }
  return (
    <Alert
      type={res.status === 'CANCELLED' ? 'error' : 'info'}
      showIcon
      message={res.message}
      action={<Link to={leaveRequestHref(res.requestId)}><Button size="small">신청 보기</Button></Link>}
    />
  );
}

/** 휴가 신청(P1, 설계 6.3): 캘린더에서 날짜를 고르면 300ms 뒤 날짜별 preview, 확인되면 상신 */
export function LeaveApply() {
  const navigate = useNavigate();
  const notify = useNotify();
  const types = useLeaveTypes();
  const booked = useBookedDates();
  const [typeId, setTypeId] = useState<number | null>(null);
  const [month, setMonth] = useState(nowKst().format('YYYY-MM'));
  const [selected, setSelected] = useState<Map<string, LeavePortion>>(new Map());
  const [reason, setReason] = useState('');
  // 화면에 들어올 때 한 번 만들고, 같은 신청을 다시 보낼 때 그대로 쓴다(설계 7장 멱등)
  const [key, setKey] = useState(() => crypto.randomUUID());
  const submit = useSubmitLeave();

  const type = types.data?.find((t) => t.leaveTypeId === typeId) ?? types.data?.[0] ?? null;
  const portions = PORTIONS[type?.allowedUnits ?? 'MIXED'];

  const dates = useMemo(() => [...selected.entries()].sort(([a], [b]) => a.localeCompare(b))
    .map(([leaveDate, portion]) => ({ leaveDate, portion })), [selected]);
  const input: LeaveInput | null = type && dates.length > 0 ? { leaveTypeId: type.leaveTypeId, reason: null, dates } : null;
  const debounced = useDebounced(input, DEBOUNCE_MS);
  const preview = useLeavePreview(debounced);
  const settled = JSON.stringify(debounced) === JSON.stringify(input);
  const pv = settled && !preview.isFetching ? preview.data : undefined;

  const errors = useMemo(() => {
    const m = new Map<string, string>();
    for (const r of pv?.reasons ?? []) if (r.date) m.set(r.date, r.message);
    return m;
  }, [pv]);
  const deductOf = new Map((pv?.dates ?? []).map((d) => [d.leaveDate, d.balanceDeductionMinutes]));
  const general = (pv?.reasons ?? []).filter((r) => !r.date);

  // 제출 결과가 처리 확인 중이면 입력을 잠근다(같은 키로 다른 내용을 보내면 서버가 거부한다)
  const pending = submit.data?.status === 'SUBMITTING';
  const locked = pending || submit.isPending;

  const toggle = (d: string) => {
    if (locked) return;
    setSelected((cur) => {
      const next = new Map(cur);
      if (next.has(d)) next.delete(d);
      else if (next.size < MAX_DATES) next.set(d, portions[0]);
      return next;
    });
  };
  const setPortion = (d: string, p: LeavePortion) => setSelected((cur) => new Map(cur).set(d, p));
  const changeType = (id: number) => {
    setTypeId(id);
    const allowed = PORTIONS[types.data?.find((t) => t.leaveTypeId === id)?.allowedUnits ?? 'MIXED'];
    setSelected(new Map([...selected].map(([d, p]) => [d, allowed.includes(p) ? p : allowed[0]])));
  };

  const reasonOk = reason.length <= REASON_MAX;
  const canSubmit = !!pv?.requestable && !!input && reasonOk && !locked;
  const send = () => {
    if (!input) return;
    submit.mutate({ key, input: { ...input, reason: reason.trim() || null } }, {
      onSuccess: (res) => {
        if (res.status === 'SUBMITTED') {
          notify.success('휴가를 신청했습니다', '결재가 끝나면 잔여에 반영됩니다');
          navigate(leaveRequestHref(res.requestId), { replace: true });
        }
        // 처리 확인 중이 아니면 이 키는 끝난 요청이다. 다음 신청은 새 키로
        if (res.status !== 'SUBMITTING') setKey(crypto.randomUUID());
      },
    });
  };
  const doSubmit = () => { if (canSubmit) send(); };

  if (types.isError) {
    if (isApiError(types.error) && types.error.code === 'LEAVE_010') return <NoPolicyNotice />;
    return <ErrorPanel error={types.error} onRetry={() => types.refetch()} />;
  }
  if (types.isPending) return <Skeleton active={false} paragraph={{ rows: 6 }} />;
  if (types.data.length === 0) return <Alert type="info" showIcon message="지금 신청할 수 있는 휴가 유형이 없습니다" />;

  const remainAfter = pv?.remainingMinutes != null ? pv.remainingMinutes - pv.balanceDeductionMinutes : null;

  return (
    <div className={s.applyGrid}>
      <Card title="날짜 선택" meta={<Link to={LEAVE_PATH}>← 휴가</Link>}>
        <div className={s.stack}>
          <div className={s.field}>
            <span className={s.label}>유형</span>
            <Select<number>
              style={{ minWidth: 160 }}
              value={type?.leaveTypeId}
              onChange={changeType}
              disabled={locked}
              aria-label="휴가 유형"
              options={types.data.map((t) => ({ value: t.leaveTypeId, label: t.leaveTypeName }))}
            />
          </div>
          <Calendar month={month} setMonth={setMonth} selected={selected} toggle={toggle} booked={booked} errors={errors} />
          <span className={s.muted}>지난 날짜는 고를 수 없습니다. 주말·공휴일·이미 신청한 날은 오른쪽 확인 결과에 표시됩니다.</span>
        </div>
      </Card>

      <Card
        title={`선택한 날짜 ${dates.length}`}
        meta={dates.length === 0 ? undefined
          : !settled || preview.isFetching ? <span className={s.muted}>확인 중…</span>
          : pv?.requestable ? <span className={s.ok}>신청 가능</span> : undefined}
      >
        <div className={s.stack}>
          {dates.length === 0 && <div className={s.muted}>왼쪽 달력에서 날짜를 고르면 날짜별 차감과 신청 후 잔여를 바로 보여 줍니다.</div>}

          {preview.isError && settled && (
            <Alert type="error" showIcon message={isApiError(preview.error) ? preview.error.message : '확인하지 못했습니다'}
                   action={<Button size="small" onClick={() => preview.refetch()}>다시 확인</Button>} />
          )}
          {pv?.validationStatus === 'UNVERIFIABLE' && (
            <Alert type="warning" showIcon message="공휴일 확인에 실패했습니다. 잠시 후 다시 확인해 주세요"
                   action={<Button size="small" onClick={() => preview.refetch()}>다시 확인</Button>} />
          )}
          {general.map((r) => <Alert key={r.code} type="error" showIcon message={r.message} />)}

          {dates.length > 0 && (
            <div className={s.rows}>
              {dates.map(({ leaveDate, portion }) => (
                <div key={leaveDate} className={s.row}>
                  <span className={`${s.rowDate} nx-num`}>{formatDate(leaveDate)}</span>
                  <Segmented<LeavePortion>
                    size="small"
                    value={portion}
                    disabled={locked}
                    onChange={(p) => setPortion(leaveDate, p)}
                    options={portions.map((p) => ({ value: p, label: SHORT[p] }))}
                    aria-label={`${formatDate(leaveDate)} 단위`}
                  />
                  <span className={`${s.rowDeduct} nx-num`}>
                    {deductOf.has(leaveDate) ? `-${formatLeaveDays(deductOf.get(leaveDate))}` : ''}
                  </span>
                  <Button size="small" type="text" icon={<Icon as={X} />} aria-label={`${formatDate(leaveDate)} 빼기`}
                          disabled={locked} onClick={() => toggle(leaveDate)} />
                  {errors.has(leaveDate) && <span className={s.rowReason} role="alert">⚠ {errors.get(leaveDate)}</span>}
                </div>
              ))}
            </div>
          )}

          {pv && (
            <div className={s.total}>
              차감 합계 <b className="nx-num">{formatLeaveDays(pv.balanceDeductionMinutes)}</b>
              {remainAfter != null && <>→ 신청 후 잔여 <b className="nx-num">{formatLeaveDays(remainAfter)}</b></>}
              {!pv.balanceRequired && <span className={s.muted}>(잔액을 차감하지 않는 휴가)</span>}
            </div>
          )}

          <Input.TextArea
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            maxLength={REASON_MAX}
            showCount
            autoSize={{ minRows: 2, maxRows: 6 }}
            aria-label="휴가 사유"
            placeholder="사유 (선택)"
            disabled={locked}
            onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); doSubmit(); } }}
          />

          {submit.isError && (
            <Alert type="error" showIcon
                   message={isApiError(submit.error) ? submit.error.message : '신청하지 못했습니다'}
                   description={isApiError(submit.error) ? <span className="nx-mono">{submit.error.code}</span> : undefined} />
          )}
          {submit.data && submit.data.status !== 'SUBMITTED' && (
            <SubmissionNotice res={submit.data} retry={send} retrying={submit.isPending} />
          )}

          <div className={s.actions}>
            <span className={s.muted}>결재선은 규정에 따라 자동으로 정해집니다</span>
            <span className={s.spacer} />
            <Button onClick={() => navigate(LEAVE_PATH)} disabled={submit.isPending}>취소</Button>
            <Button type="primary" disabled={!canSubmit} loading={submit.isPending} onClick={doSubmit}>
              신청 <Kbd onPrimary>{modKey}</Kbd>+<Kbd onPrimary>Enter</Kbd>
            </Button>
          </div>
        </div>
      </Card>
    </div>
  );
}
