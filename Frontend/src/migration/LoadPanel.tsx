import { useState } from 'react';
import { Alert, Button, Input, Modal, Tooltip } from 'antd';
import {
  useLoad, useMigrationFile, usePreviewLoad, useReferencePlan, useRollbackBatch,
  type BatchEntity, type BatchView, type LoadPreview, type MigrationDetail, type MigrationEntity, type MigrationFileSummary,
  type ReferencePlanItem, type ReferenceType,
} from '@/api/migration';
import { StatusBadge } from '@/ui/StatusBadge';
import { ConfirmAction } from '@/ui/ConfirmAction';
import { useNotify } from '@/ui/useNotify';
import { formatDateTime, formatNumber, serverUtc } from '@/format';
import { BATCH_ORDER, ENTITY_LABEL, ENTITY_ORDER, ENTITY_SCHEMAS, batchEntityLabel } from './schemas';
import s from './migration.module.css';

/** 미리보기 예시 표: 대상 필드 라벨로 열을 만든다(필드 순서는 EntitySchemas 순서) */
function SampleTable({ entity, sample }: { entity: MigrationEntity; sample: Record<string, unknown>[] }) {
  if (sample.length === 0) return <span className={s.muted}>예시 행이 없습니다.</span>;
  const keys = new Set(sample.flatMap((r) => Object.keys(r)));
  const cols = ENTITY_SCHEMAS[entity].filter((f) => keys.has(f.field));
  const extra = [...keys].filter((k) => !cols.some((c) => c.field === k));
  return (
    <div style={{ overflowX: 'auto' }}>
      <table className={s.table}>
        <thead>
          <tr>{cols.map((c) => <th key={c.field}>{c.label}</th>)}{extra.map((k) => <th key={k} className="nx-mono">{k}</th>)}</tr>
        </thead>
        <tbody>
          {sample.map((r, i) => (
            <tr key={i}>
              {[...cols.map((c) => c.field), ...extra].map((k) => (
                <td key={k} className={s.value}>{r[k] == null || r[k] === '' ? '—' : String(r[k])}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * 되돌리기 칸(P1, 설계 6.7·7.2). 지금 되돌릴 수 있는 묶음(적재됨 + 뒤 순서 묶음이 적재돼 있지 않음)에만 버튼을 둔다.
 * 막힌 적재됨 묶음에는 무엇을 먼저 되돌려야 하는지 적는다. 순서 판단은 서버(LOAD_006)가 최종이다.
 */
function RollbackCell({ d, b }: { d: MigrationDetail; b: BatchView }) {
  const notify = useNotify();
  const rollback = useRollbackBatch(d.migrationId);
  if (b.status !== 'LOADED' || d.status === 'COMPLETED') return null;
  const order = (e: string) => BATCH_ORDER.indexOf(e as BatchEntity);
  const later = [...new Set(d.batches
    .filter((x) => x.status === 'LOADED' && order(x.entity) > order(b.entity))
    .sort((x, y) => order(y.entity) - order(x.entity))
    .map((x) => batchEntityLabel(x.entity)))];
  if (later.length) return <span className={s.muted}>{later.join(' → ')} 먼저 되돌려야 합니다</span>;

  const label = batchEntityLabel(b.entity);
  return (
    <ConfirmAction
      title={`${label} 적재를 되돌립니다`}
      confirmLabel="되돌리기"
      danger
      typeToConfirm={d.name}
      onConfirm={() => rollback.mutateAsync(b.batchId).then(
        () => notify.success(`${label} 적재를 되돌렸습니다`),
        (e) => notify.error(e, '되돌리지 못했습니다'),
      )}
      trigger={(open) => <Button size="small" onClick={open}>되돌리기</Button>}
    >
      <p>이 묶음으로 들어간 <b>{label} {formatNumber(b.sourceRows)}행</b>(생성 {formatNumber(b.created)} · 수정 {formatNumber(b.updated)})을 대상 서비스에서 되돌립니다.</p>
      <p className={s.muted}>되돌린 뒤에는 이 파일을 다시 고치거나 적재할 수 있습니다.</p>
    </ConfirmAction>
  );
}

function BatchRow({ d, b }: { d: MigrationDetail; b: BatchView }) {
  return (
    <tr>
      <td>{batchEntityLabel(b.entity)}</td>
      <td><StatusBadge domain="BatchStatus" value={b.status} /></td>
      <td className="nx-num">{formatNumber(b.sourceRows)}행</td>
      <td className="nx-num">생성 {formatNumber(b.created)} · 수정 {formatNumber(b.updated)}</td>
      <td className="nx-num">{formatDateTime(serverUtc(b.finishedAt ?? b.startedAt), true)}</td>
      <td className={s.value}>{b.error ?? ''}</td>
      <td><RollbackCell d={d} b={b} /></td>
    </tr>
  );
}

const REF_TYPE_LABEL: Record<ReferenceType, string> = { POSITION: '직위', GRADE: '직급', DUTY: '직책', JOB_CODE: '직무' };
const REF_STATUS_ORDER: Record<ReferencePlanItem['status'], number> = { AMBIGUOUS: 0, NEW: 1, EXISTS: 2 };

/**
 * 사원 적재 때 같이 들어가는 기준정보(직위·직급·직책·직무). 새로 만들 값과 막히는 값을 먼저 보여 준다.
 * 직급은 호봉까지 붙어 나온다('G5 1호봉'). 호봉이 없어 1호봉으로 채운 값은 message로 알린다.
 */
function ReferencePlan({ items }: { items: ReferencePlanItem[] }) {
  const count = (st: ReferencePlanItem['status']) => items.filter((i) => i.status === st).length;
  const sorted = [...items].sort((a, b) => REF_STATUS_ORDER[a.status] - REF_STATUS_ORDER[b.status]
    || a.type.localeCompare(b.type) || a.value.localeCompare(b.value, 'ko'));
  return (
    <div className={s.section}>
      <h4 className={s.h4}>
        같이 적재할 기준정보 · 새로 만듦 {formatNumber(count('NEW'))} · 이미 있음 {formatNumber(count('EXISTS'))}
        {count('AMBIGUOUS') > 0 && ` · 고를 수 없음 ${formatNumber(count('AMBIGUOUS'))}`}
      </h4>
      <div className={s.scrollBox}>
        <table className={s.table}>
          <thead><tr><th>종류</th><th>값</th><th>상태</th><th>안내</th></tr></thead>
          <tbody>
            {sorted.map((i) => (
              <tr key={`${i.type}-${i.value}`}>
                <td>{REF_TYPE_LABEL[i.type] ?? i.type}</td>
                <td className={s.value}>{i.value}</td>
                <td><StatusBadge domain="ReferencePlanStatus" value={i.status} /></td>
                <td className={s.muted}>{i.message ?? (i.type === 'POSITION' && i.status === 'NEW' && i.rankOrder != null ? `서열 ${i.rankOrder}` : '')}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/**
 * ④ 미리보기 · ⑤ 적재(설계 6.7, 요구사항 4.5). entity 탭의 최신 파일 하나만 보낸다.
 * - 미리보기는 적재 불가·자동 정리가 0건일 때만 연다(비활성 사유 툴팁). 서버 loadable이 최종 판단이다.
 * - 적재 모달은 건수를 적고, 작업명을 그대로 입력해야 [적재]가 켜진다.
 * - 사원은 조직이 먼저 적재돼 있어야 부서를 찾는다 → 조직 적재 전이면 막는다.
 * - 사원 파일은 서버가 기준정보(HR_CODE) 묶음을 같은 fileId로 먼저 넣는다 → 묶음은 entity로 구분한다.
 *   기준정보 중 고를 수 없는 값(AMBIGUOUS)이 있으면 적재가 409 LOAD_009로 막히므로 미리 끈다.
 */
export function LoadPanel({ d, entity, file, onPreview }: {
  d: MigrationDetail; entity: MigrationEntity; file: MigrationFileSummary;
  /** 미리보기 결과(적재 가능 여부). 적재하거나 다시 막히면 false. 단계 표시가 쓴다 */
  onPreview?: (loadable: boolean) => void;
}) {
  const notify = useNotify();
  const detail = useMigrationFile(d.migrationId, file.fileId);
  const preview = usePreviewLoad(d.migrationId);
  const load = useLoad(d.migrationId);
  const [result, setResult] = useState<LoadPreview | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [typed, setTyped] = useState('');

  const totals = detail.data?.issueTotals;
  const mappedOk = !!detail.data && detail.data.missingRequired.length === 0 && detail.data.columnMappings.every((m) => m.confirmed);
  const batches = d.batches.filter((b) => b.fileId === file.fileId).sort((a, b) => b.startedAt.localeCompare(a.startedAt));
  const loaded = batches.some((b) => b.entity === entity && b.status === 'LOADED');
  const refs = useReferencePlan(d.migrationId, entity === 'HR_EMPLOYEE' && mappedOk);
  const refItems = (refs.data ?? []).filter((i) => i.fileId === file.fileId);
  const ambiguous = refItems.filter((i) => i.status === 'AMBIGUOUS').length;
  // 조직 → 사원 순서: 앞 entity가 하나라도 적재돼 있어야 한다
  const prev = ENTITY_ORDER.slice(0, ENTITY_ORDER.indexOf(entity));
  const prevMissing = prev.filter((e) => !d.batches.some((b) => b.entity === e && b.status === 'LOADED'));

  const blockedReason = !detail.data ? '파일 정보를 불러오는 중입니다'
    : !mappedOk ? '매핑을 먼저 확정해 주세요'
    : totals && totals.BLOCK > 0 ? `적재 불가 ${totals.BLOCK}건을 먼저 해결해 주세요`
    : totals && totals.FIX > 0 ? `자동 정리 ${totals.FIX}건을 먼저 적용해 주세요`
    : null;
  // 미리보기 뒤에 행 수정 등으로 다시 막히면 그 미리보기는 무효다
  const loadReason = blockedReason ? blockedReason
    : prevMissing.length ? `${prevMissing.map((e) => ENTITY_LABEL[e]).join('·')}을(를) 먼저 적재해 주세요`
    : !result ? '미리보기를 먼저 확인해 주세요'
    : !result.loadable ? '미리보기에서 적재할 수 없다고 나왔습니다'
    : ambiguous > 0 ? `고를 수 없는 기준정보 ${ambiguous}건이 있습니다(직급에 호봉을 적어 주세요)`
    : null;

  const runPreview = () => preview.mutate([file.fileId], {
    onSuccess: (r) => { setResult(r); onPreview?.(r.loadable); },
    onError: (e) => notify.error(e, '미리보기를 만들지 못했습니다'),
  });
  const runLoad = () => load.mutate([file.fileId], {
    onSuccess: (r) => {
      setConfirmOpen(false);
      setTyped('');
      setResult(null);
      onPreview?.(false);
      // 이번 요청의 묶음: 이 파일의 기준정보(사원일 때) + 본 묶음. 응답에는 작업 전체 묶음이 오므로 가장 최근 것만 본다
      const latest = (e: string) => r.batches.filter((x) => x.fileId === file.fileId && x.entity === e)
        .sort((a, c) => c.startedAt.localeCompare(a.startedAt))[0];
      const code = entity === 'HR_EMPLOYEE' ? latest('HR_CODE') : undefined;
      const b = latest(entity);
      // 대상 서비스 실패도 200으로 온다 → batch 상태로 판단한다. 앞 묶음이 실패하면 뒤 묶음은 진행하지 않는다
      const failed = [code, b].find((x) => x?.status === 'FAILED');
      if (failed) notify.error(new Error(failed.error ?? ''), `${batchEntityLabel(failed.entity)} 적재가 실패했습니다`);
      else notify.success(`${ENTITY_LABEL[entity]} ${formatNumber(b?.sourceRows ?? 0)}행을 적재했습니다`,
        [code && `기준정보 생성 ${formatNumber(code.created)}`, b && `생성 ${formatNumber(b.created)} · 수정 ${formatNumber(b.updated)}`]
          .filter(Boolean).join(' · ') || undefined);
    },
    onError: (e) => notify.error(e, '적재하지 못했습니다'),
  });

  // 사원 파일은 기준정보(HR_CODE) 항목이 같은 fileId로 먼저 온다 → entity까지 맞춘다
  const item = result?.files.find((f) => f.fileId === file.fileId && f.entity === entity);
  return (
    <div className={s.section}>
      <div className={s.foot}>
        <Tooltip title={blockedReason}>
          <Button onClick={runPreview} loading={preview.isPending} disabled={!!blockedReason}>④ 미리보기(dry run)</Button>
        </Tooltip>
        <Tooltip title={loadReason}>
          <Button type="primary" onClick={() => setConfirmOpen(true)} disabled={!!loadReason}>⑤ 적재</Button>
        </Tooltip>
        {/* 꺼진 이유를 툴팁에만 두지 않고 글자로도 적는다 */}
        {!loaded && (blockedReason || (result && loadReason)) && <span className={s.pending}>{blockedReason ?? loadReason}</span>}
        {loaded && <span className={s.muted}>이 파일은 적재됐습니다. 다시 적재하면 기준 키(부서코드·사번)로 덮어씁니다.</span>}
      </div>

      {prevMissing.length > 0 && (
        <Alert type="info" showIcon message={`${prevMissing.map((e) => ENTITY_LABEL[e]).join('·')}을(를) 먼저 적재합니다`}
               description="사원의 부서는 조직 파일의 부서코드·부서명으로 찾습니다." />
      )}

      {entity === 'HR_EMPLOYEE' && refItems.length > 0 && <ReferencePlan items={refItems} />}

      {result && (
        <div className={s.section}>
          {result.loadable
            ? <Alert type="success" showIcon message={`${ENTITY_LABEL[entity]} ${formatNumber(item?.rows ?? file.rows)}행을 적재할 수 있습니다`}
                     description="아래는 자동 정리가 반영된 값 예시입니다. 실제로는 아직 보내지 않았습니다." />
            : <Alert type="warning" showIcon message="적재할 수 없습니다"
                     description={`적재 불가 ${formatNumber(result.blockIssues)}건 · 자동 정리 ${formatNumber(result.fixIssues)}건이 남아 있습니다.`} />}
          {item && <SampleTable entity={entity} sample={item.sample} />}
        </div>
      )}

      {batches.length > 0 && (
        <div className={s.section}>
          <h4 className={s.h4}>적재 기록</h4>
          <table className={s.table}>
            <thead><tr><th>종류</th><th>상태</th><th>원본</th><th>결과</th><th>시각</th><th>오류</th><th /></tr></thead>
            <tbody>{batches.map((b) => <BatchRow key={b.batchId} d={d} b={b} />)}</tbody>
          </table>
        </div>
      )}

      <Modal open={confirmOpen} title={`${ENTITY_LABEL[entity]} 적재`} onCancel={() => { setConfirmOpen(false); setTyped(''); }}
             okText="적재" okButtonProps={{ disabled: typed !== d.name, loading: load.isPending }} onOk={runLoad} destroyOnHidden>
        <p><b>{entity} {formatNumber(item?.rows ?? file.rows)}건</b>이 실제 DB에 적재됩니다.</p>
        <p className={s.muted}>확인을 위해 작업명 <b>{d.name}</b>을(를) 그대로 입력해 주세요.</p>
        <Input value={typed} onChange={(e) => setTyped(e.target.value)} placeholder={d.name} aria-label="작업명 확인"
               onPressEnter={() => typed === d.name && runLoad()} />
      </Modal>
    </div>
  );
}
