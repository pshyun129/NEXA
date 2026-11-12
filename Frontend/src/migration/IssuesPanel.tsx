import { useState } from 'react';
import { Button, Input, Segmented, Skeleton, Table, Tooltip } from 'antd';
import {
  useApplyFixes, useIssues, useUpdateRow, type IssueGroup, type IssueTotals, type Severity, type ValidationIssue,
} from '@/api/migration';
import { Icon } from '@/ui/Icon';
import { Pencil, Wand, WandSparkles } from '@/ui/icons';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { StatusBadge } from '@/ui/StatusBadge';
import { AiMark, EmptyState } from '@/ui/primitives';
import { useNotify } from '@/ui/useNotify';
import { useCountUp } from '@/common/useCountUp';
import { formatNumber } from '@/format';
import s from './migration.module.css';

const SEVS: Severity[] = ['BLOCK', 'WARN', 'FIX'];
const SEV_COLOR: Record<Severity, string> = { BLOCK: 'var(--nx-danger-fg)', WARN: 'var(--nx-warning-fg)', FIX: 'var(--nx-info-fg)' };
const PAGE_SIZE = 50;
/** AI 표식은 LLM이 값을 추천한 묶음에만(설계 6.7) */
const AI_CODES = new Set(['ENUM_VALUE_SUGGESTED']);
/** 일괄 적용 버튼 앞 아이콘(style-guide 버튼 계층 '일괄 적용'): wand n-600, AI 추천 묶음만 wand-sparkles ai-700. 보라 채움 금지 */
const ApplyIcon = ({ ai }: { ai?: boolean }) => ai
  ? <Icon as={WandSparkles} size={16} style={{ color: 'var(--nx-ai-700)' }} />
  : <Icon as={Wand} size={16} style={{ color: 'var(--nx-n-600)' }} />;

function Count({ sev, n }: { sev: Severity; n: number }) {
  const shown = useCountUp(n);
  return (
    <span className={s.total}>
      <StatusBadge domain="Severity" value={sev} />
      <b className="nx-num" style={{ color: n > 0 ? SEV_COLOR[sev] : undefined }}>{formatNumber(shown)}</b>
    </span>
  );
}

/** 이슈 건수 3구간 막대(행 수 아님, 설계 6.7) */
function TotalsBar({ t }: { t: IssueTotals }) {
  const sum = t.BLOCK + t.WARN + t.FIX;
  return (
    <div className={s.bar} role="img" aria-label={`적재 불가 ${t.BLOCK}, 확인 필요 ${t.WARN}, 자동 정리 ${t.FIX}`}>
      {sum === 0 ? <div className={s.barEmpty} /> : SEVS.map((k) => t[k] > 0 && (
        <div key={k} className={s.barSeg} style={{ width: `${(t[k] / sum) * 100}%`, background: SEV_COLOR[k] }} />
      ))}
    </div>
  );
}

const rowKey = (r: ValidationIssue) => `${r.rowNo}-${r.sourceColumn}-${r.code}-${r.value}`;
/** 직접 고칠 수 있는 이슈: 행·원본 칸이 있고 자동 정리 대상이 아닌 것(자동 정리는 [적용]으로) */
const editable = (r: ValidationIssue) => r.rowNo != null && !!r.sourceColumn && r.severity !== 'FIX';

/**
 * 값 칸 인라인 편집(설계 6.7 ✎, P1): ✎ → 입력 → Enter 저장(PATCH rows/{rowNo}) → 서버가 다시 검증 → 건수 갱신.
 * 그 칸에 이슈가 남으면 입력을 닫지 않고 남은 문제를 아래에 적는다. Esc는 취소.
 */
function ValueCell({ r, migrationId, fileId, editing, onEdit, onClose }: {
  r: ValidationIssue; migrationId: number; fileId: number; editing: boolean; onEdit: () => void; onClose: () => void;
}) {
  const notify = useNotify();
  const update = useUpdateRow(migrationId, fileId);
  const [draft, setDraft] = useState(r.value ?? r.suggestion ?? '');
  const [left, setLeft] = useState<string | null>(null);
  if (!editing) {
    return (
      <span className={s.valueCell}>
        <span className={s.value}>{r.value ?? '—'}</span>
        {editable(r) && (
          <Tooltip title="이 칸 고치기">
            <Button size="small" type="text" aria-label={`${r.rowNo}행 ${r.sourceColumn} 고치기`} onClick={onEdit}
                    icon={<Icon as={Pencil} size={12} />} />
          </Tooltip>
        )}
      </span>
    );
  }
  const save = () => update.mutate({ rowNo: r.rowNo!, values: { [r.sourceColumn!]: draft } }, {
    onSuccess: (res) => {
      const same = res.rowIssues.filter((i) => i.sourceColumn === r.sourceColumn);
      if (same.length) { setLeft(same.map((i) => i.message).join(' / ')); return; }
      onClose();
      notify.success(`${r.rowNo}행 ${r.sourceColumn}을(를) 고쳤습니다`,
        `이 파일 남은 이슈 적재 불가 ${res.issueTotals.BLOCK} · 확인 필요 ${res.issueTotals.WARN} · 자동 정리 ${res.issueTotals.FIX}`);
    },
    onError: (e) => notify.error(e, '행을 고치지 못했습니다'),
  });
  return (
    <div className={s.editCell}>
      <Input size="small" autoFocus value={draft} disabled={update.isPending} aria-label={`${r.rowNo}행 ${r.sourceColumn} 새 값`}
             onChange={(e) => { setDraft(e.target.value); setLeft(null); }}
             onPressEnter={save} onKeyDown={(e) => { if (e.key === 'Escape') onClose(); }} />
      <span className={left ? s.pending : s.muted}>{left ?? (update.isPending ? '다시 검증하는 중…' : 'Enter 저장 · Esc 취소')}</span>
    </div>
  );
}

/** ③ 검증·자동 정리(설계 6.7): 합계 → 원인별 묶음(+ [적용]) → 행 단위(서버 페이징, 값 칸 ✎ 직접 수정) */
export function IssuesPanel({ migrationId, fileId, rows }: { migrationId: number; fileId: number; rows: number }) {
  const notify = useNotify();
  const fix = useApplyFixes(migrationId);
  const [severity, setSeverity] = useState<Severity | undefined>(undefined);
  const [page, setPage] = useState(0);
  const [editKey, setEditKey] = useState<string | null>(null);
  const q = useIssues(migrationId, { fileId, severity, page, size: PAGE_SIZE });

  if (q.isError) return <ErrorPanel error={q.error} onRetry={() => q.refetch()} />;
  if (!q.data) return <Skeleton active={false} paragraph={{ rows: 6 }} />;
  const { totals, groups, content, totalElements } = q.data;

  const apply = (scope: { fileId: number; code?: string; field?: string | null }, label: string) =>
    fix.mutate(scope, {
      // 화면 상단 합계처럼 이 파일 기준으로 보여 준다
      onSuccess: (res) => {
        const t = res.fileTotals ?? res.issueTotals;
        notify.success(`${label}: ${formatNumber(res.applied)}칸을 고쳤습니다`,
          `이 파일 남은 이슈 적재 불가 ${t.BLOCK} · 확인 필요 ${t.WARN} · 자동 정리 ${t.FIX}`);
      },
      onError: (e) => notify.error(e, '자동 정리를 적용하지 못했습니다'),
    });

  const cols = [
    { title: '행', dataIndex: 'rowNo', width: 70, render: (v: number | null) => <span className="nx-num">{v ?? '파일'}</span> },
    { title: '칸', dataIndex: 'sourceColumn', width: 120, render: (v: string | null) => v ?? '—' },
    {
      title: '값', dataIndex: 'value', width: 200,
      render: (_: string | null, r: ValidationIssue) => (
        <ValueCell key={rowKey(r)} r={r} migrationId={migrationId} fileId={fileId} editing={editKey === rowKey(r)}
                   onEdit={() => setEditKey(rowKey(r))} onClose={() => setEditKey(null)} />
      ),
    },
    { title: '구분', dataIndex: 'severity', width: 110, render: (v: Severity) => <StatusBadge domain="Severity" value={v} /> },
    { title: '문제', dataIndex: 'message' },
    { title: '정리 값', dataIndex: 'suggestion', width: 160, render: (v: string | null) => v ? <span className={s.value}>{v}</span> : '—' },
  ];

  return (
    <div className={s.section}>
      <div className={s.totals}>
        <span className={s.muted}><b className="nx-num">{formatNumber(rows)}</b>행 · 이슈 건수</span>
        {SEVS.map((k) => <Count key={k} sev={k} n={totals[k]} />)}
        <span className={s.spacer} />
        <Button icon={<ApplyIcon />} disabled={totals.FIX === 0} loading={fix.isPending && fix.variables?.code == null}
                onClick={() => apply({ fileId }, '자동 정리')}>
          자동 정리 {formatNumber(totals.FIX)}건 적용
        </Button>
      </div>
      <TotalsBar t={totals} />

      <div className={s.issuesGrid}>
        <section>
          <h4 className={s.h4}>원인별 묶음</h4>
          {groups.length === 0 ? <EmptyState title={severity ? '이 구분의 이슈가 없습니다' : '이슈가 없습니다'} /> : (
            <ul className={s.groups}>
              {groups.map((g: IssueGroup) => (
                <li key={`${g.code}-${g.field ?? ''}`}>
                  <StatusBadge domain="Severity" value={g.severity} />
                  {AI_CODES.has(g.code) && <AiMark label="AI 추천" />}
                  <span className={s.groupText}>
                    <span>{g.message}</span>
                    <span className={s.muted}>
                      {g.sourceColumn ?? g.field ?? '파일'} · 예: {g.sampleValues.slice(0, 2).join(', ') || '—'}
                      {g.sampleSuggestion && <> → {g.sampleSuggestion}</>}
                    </span>
                  </span>
                  <b className="nx-num">{formatNumber(g.count)}</b>
                  {g.severity === 'FIX' && (
                    <Button size="small" icon={<ApplyIcon ai={AI_CODES.has(g.code)} />} disabled={fix.isPending}
                            loading={fix.isPending && fix.variables?.code === g.code && fix.variables?.field === g.field}
                            onClick={() => apply({ fileId, code: g.code, field: g.field }, g.message)}>
                      적용
                    </Button>
                  )}
                </li>
              ))}
            </ul>
          )}
        </section>

        <section>
          <div className={s.foot}>
            <h4 className={s.h4}>행 단위</h4>
            <span className={s.spacer} />
            <Segmented<string>
              size="small"
              value={severity ?? 'ALL'}
              onChange={(v) => { setSeverity(v === 'ALL' ? undefined : (v as Severity)); setPage(0); }}
              options={[{ value: 'ALL', label: '전체' }, { value: 'BLOCK', label: '적재 불가' }, { value: 'WARN', label: '확인 필요' }, { value: 'FIX', label: '자동 정리' }]}
            />
          </div>
          <Table<ValidationIssue>
            size="small"
            rowKey={rowKey}
            loading={q.isFetching}
            columns={cols}
            dataSource={content}
            locale={{ emptyText: <EmptyState title="이슈가 없습니다" /> }}
            pagination={totalElements > PAGE_SIZE
              ? { current: page + 1, pageSize: PAGE_SIZE, total: totalElements, showSizeChanger: false, size: 'small', onChange: (p) => setPage(p - 1) }
              : false}
          />
        </section>
      </div>
    </div>
  );
}
