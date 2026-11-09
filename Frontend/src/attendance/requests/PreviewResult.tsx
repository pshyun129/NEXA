import { Alert } from 'antd';
import type { WorkTime } from '@/api/attendance';
import type { RequestPreview } from '@/api/attendanceRequests';
import { isApiError } from '@/api/ApiError';
import { formatDate, formatDurationShort } from '@/format';
import s from './requests.module.css';

const ROWS: [keyof WorkTime, string][] = [
  ['totalMin', '총 근로'],
  ['regularMin', '소정'],
  ['otExtendedMin', '연장'],
  ['otNightMin', '초과 야간'],
  ['otHolidayMin', '휴일'],
  ['breakMin', '휴게'],
  ['approvedOtMin', '승인 초과'],
  ['unapprovedOtMin', '미승인 초과'],
];

const dur = (m: number) => (m ? formatDurationShort(m) : '—');

function Weekly({ total, max }: { total: number; max: number }) {
  return (
    <div className={s.weekly}>
      주간 합계 <b className="nx-num">{formatDurationShort(total)}</b> / 최대 <span className="nx-num">{formatDurationShort(max)}</span>
      <span className={s.muted}> · 남은 한도 <span className="nx-num">{formatDurationShort(Math.max(0, max - total))}</span></span>
    </div>
  );
}

/** preview 결과(요구사항 3.3): 근무시간 수정은 전후 비교, 초과근무는 신청분·주간 합계·남은 시간 */
export function PreviewResult({ data }: { data: RequestPreview }) {
  if (data.timeFix) {
    const { before, after, weeklyTotalMin, weeklyMaxMin } = data.timeFix;
    return (
      <div className={s.preview}>
        <table className={s.cmp}>
          <thead><tr><th /><th>수정 전</th><th>수정 후</th></tr></thead>
          <tbody>
            {ROWS.map(([k, label]) => {
              const changed = before[k] !== after[k];
              return (
                <tr key={k} className={changed ? s.changed : undefined}>
                  <th scope="row">{label}</th>
                  <td className="nx-num">{dur(before[k])}</td>
                  <td className="nx-num">{dur(after[k])}{changed && <span className="nx-sr-only"> (바뀜)</span>}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
        <Weekly total={weeklyTotalMin} max={weeklyMaxMin} />
      </div>
    );
  }
  if (data.overtime) {
    const o = data.overtime;
    return (
      <div className={s.preview}>
        <dl className={s.kv}>
          <div><dt>신청 시간</dt><dd className="nx-num">{formatDurationShort(o.requestedMin)}</dd></div>
          <div><dt>주간 합계(신청 포함)</dt><dd className="nx-num">{formatDurationShort(o.weeklyTotalMin)} / {formatDurationShort(o.weeklyMaxMin)}</dd></div>
          <div><dt>남은 한도</dt><dd className="nx-num">{formatDurationShort(o.remainMin)}</dd></div>
        </dl>
      </div>
    );
  }
  if (data.holidaySubst) {
    const h = data.holidaySubst;
    return (
      <div className={s.preview}>
        <dl className={s.kv}>
          <div><dt>근무한 휴일</dt><dd className="nx-num">{formatDate(h.targetDate)}</dd></div>
          <div><dt>대신 쉴 날</dt><dd className="nx-num">{formatDate(h.substDate)}</dd></div>
        </dl>
      </div>
    );
  }
  return null;
}

/** preview·상신 오류. 서버가 첫 오류 하나만 돌려주므로 그대로 보여 준다 */
export function RequestError({ error }: { error: unknown }) {
  const e = isApiError(error) ? error : null;
  const hint = e?.code === 'ATTN_011' ? ' 신청 시간을 줄이거나 다른 날로 나눠 신청해 주세요.'
    : e?.code === 'ATTN_010' ? ' 기존 신청을 회수하거나 결과가 나온 뒤 다시 신청해 주세요.'
    : '';
  return (
    <Alert
      type="error"
      showIcon
      message={(e?.message ?? '확인하지 못했습니다.') + hint}
      description={e ? <span className="nx-mono">{e.code}{e.requestId ? ` · ${e.requestId}` : ''}</span> : undefined}
    />
  );
}
