import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Select, Skeleton, Tooltip } from 'antd';
import { useMigrationFile, useSaveMapping, type MigrationEntity } from '@/api/migration';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { AiMark } from '@/ui/primitives';
import { StatusBadge } from '@/ui/StatusBadge';
import { useNotify } from '@/ui/useNotify';
import { formatNumber } from '@/format';
import { ENTITY_SCHEMAS } from './schemas';
import s from './migration.module.css';

const TYPE_LABEL: Record<string, string> = { DATE: '날짜', NUMBER: '숫자', STRING: '문자', EMPTY: '빈 칸' };

/** ③ 매핑(설계 6.7): 원본 칸 → 대상 필드. 시스템 추천은 확인 전까지 '추천', LLM 추천은 ✦ + 근거 툴팁 */
export function MappingTable({ migrationId, fileId, entity }: { migrationId: number; fileId: number; entity: MigrationEntity }) {
  const q = useMigrationFile(migrationId, fileId);
  const save = useSaveMapping(migrationId, fileId);
  const notify = useNotify();
  const fields = ENTITY_SCHEMAS[entity];
  const [draft, setDraft] = useState<Record<string, string | null>>({});

  useEffect(() => {
    if (q.data) setDraft(Object.fromEntries(q.data.columnMappings.map((m) => [m.sourceColumn, m.targetField])));
  }, [q.data]);

  const used = useMemo(() => {
    const m = new Map<string, number>();
    Object.values(draft).forEach((f) => { if (f) m.set(f, (m.get(f) ?? 0) + 1); });
    return m;
  }, [draft]);

  if (q.isError) return <ErrorPanel error={q.error} onRetry={() => q.refetch()} />;
  if (!q.data) return <Skeleton active={false} paragraph={{ rows: 6 }} />;
  const d = q.data;
  const profile = new Map(d.columns.map((c) => [c.sourceColumn, c]));
  const unconfirmed = d.columnMappings.some((m) => !m.confirmed);
  const dup = [...used.entries()].filter(([, n]) => n > 1).map(([f]) => f);
  const dirty = d.columnMappings.some((m) => (draft[m.sourceColumn] ?? null) !== m.targetField);

  const submit = () => save.mutate(
    d.columnMappings.map((m) => ({ sourceColumn: m.sourceColumn, targetField: draft[m.sourceColumn] ?? null })),
    {
      onSuccess: (res) => notify.success('매핑을 저장하고 다시 검증했습니다',
        res.missingRequired.length ? `필수 필드 누락: ${res.missingRequired.join(', ')}` : undefined),
      onError: (e) => notify.error(e, '매핑을 저장하지 못했습니다'),
    },
  );

  const options = fields.map((f) => ({
    value: f.field,
    label: `${f.label}${f.required || f.key ? ' *' : ''}`,
    disabled: used.has(f.field),
  }));

  return (
    <div className={s.section}>
      {d.missingRequired.length > 0 && (
        <Alert type="warning" showIcon message={`필수 필드가 연결되지 않았습니다: ${d.missingRequired.join(', ')}`}
               description="원본에 해당 칸이 있으면 연결하고, 없으면 파일에 칸을 추가해 다시 올려 주세요." />
      )}
      {dup.length > 0 && <Alert type="error" showIcon message="한 필드에 여러 칸을 연결할 수 없습니다" />}
      <table className={s.table}>
        <thead>
          <tr><th>원본 칸</th><th>값 형태</th><th>대상 필드</th><th>추천</th></tr>
        </thead>
        <tbody>
          {d.columnMappings.map((m) => {
            const p = profile.get(m.sourceColumn);
            const cur = draft[m.sourceColumn] ?? null;
            return (
              <tr key={m.sourceColumn}>
                <th scope="row">{m.sourceColumn}</th>
                <td className={s.muted}>
                  {p ? <>{TYPE_LABEL[p.detectedType] ?? p.detectedType} · 빈 값 {Math.round(p.nullRate * 100)}% · 고유 {formatNumber(p.distinctCount)}</> : '—'}
                </td>
                <td>
                  <Select
                    allowClear
                    placeholder="이관하지 않음"
                    style={{ width: 200 }}
                    aria-label={`${m.sourceColumn} 대상 필드`}
                    value={cur ?? undefined}
                    options={options.map((o) => ({ ...o, disabled: o.disabled && o.value !== cur }))}
                    onChange={(v) => setDraft((x) => ({ ...x, [m.sourceColumn]: v ?? null }))}
                  />
                </td>
                <td>
                  {m.suggestedBy === 'LLM' ? (
                    <Tooltip title={m.reason ?? 'AI가 추천한 매핑입니다'}><span><AiMark label="AI 추천" /></span></Tooltip>
                  ) : m.suggestedBy ? <StatusBadge domain="SuggestedBy" value={m.suggestedBy} /> : <span className={s.muted}>—</span>}
                  {!m.confirmed && <span className={s.pending}> · 확인 전</span>}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <div className={s.foot}>
        <span className={s.muted}>* 필수 필드. 저장하면 모든 칸이 확정되고 파일을 다시 검증합니다.</span>
        <span className={s.spacer} />
        <Button type="primary" loading={save.isPending} disabled={dup.length > 0 || (!dirty && !unconfirmed)} onClick={submit}>
          {unconfirmed && !dirty ? '추천대로 확정' : '매핑 저장'}
        </Button>
      </div>
    </div>
  );
}
