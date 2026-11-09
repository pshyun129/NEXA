import { Checkbox, TimePicker } from 'antd';
import dayjs from 'dayjs';
import { kst } from '@/format';
import { toOffsetIso } from './links';

/** 근무일 기준 시각: HH:mm + 다음 날 여부(서버는 근무일 당일·다음 날만 허용, 요구사항 3.3) */
export interface TimeVal { time: string | null; nextDay: boolean }

export const emptyTime: TimeVal = { time: null, nextDay: false };

/** 서버 ISO → 근무일 기준 TimeVal */
export function timeFromIso(workDate: string, iso: string | null | undefined): TimeVal {
  if (!iso) return emptyTime;
  const d = kst(iso);
  return { time: d.format('HH:mm'), nextDay: d.format('YYYY-MM-DD') !== workDate };
}

/** TimeVal → 서울 오프셋 ISO. 시각이 없으면 undefined */
export function timeToIso(workDate: string, v: TimeVal): string | undefined {
  if (!v.time) return undefined;
  const [h, m] = v.time.split(':').map(Number);
  const d = kst(`${workDate}T00:00:00+09:00`).add(v.nextDay ? 1 : 0, 'day').hour(h).minute(m).second(0);
  return toOffsetIso(d);
}

export function TimeField({ value, onChange, label, disabled }:
  { value: TimeVal; onChange: (v: TimeVal) => void; label: string; disabled?: boolean }) {
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
      <TimePicker
        format="HH:mm"
        needConfirm={false}
        disabled={disabled}
        aria-label={label}
        placeholder="시:분"
        style={{ width: 104 }}
        value={value.time ? dayjs(value.time, 'HH:mm') : null}
        onChange={(t) => onChange({ ...value, time: t ? t.format('HH:mm') : null })}
      />
      <Checkbox checked={value.nextDay} disabled={disabled} onChange={(e) => onChange({ ...value, nextDay: e.target.checked })}>
        다음 날
      </Checkbox>
    </span>
  );
}
