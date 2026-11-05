import { useState } from 'react';
import { Alert, Button, DatePicker, Input, InputNumber, Skeleton, Tabs, Upload } from 'antd';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  useCreateMigration, useMigration, useMigrationFile, useUploadMigrationFile,
  type MigrationDetail, type MigrationEntity, type MigrationFileSummary,
} from '@/api/migration';
import { Card, EmptyState, PageHeader } from '@/ui/primitives';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { StatusBadge } from '@/ui/StatusBadge';
import { useNotify } from '@/ui/useNotify';
import { useViewport } from '@/shell/useViewport';
import { formatDate, formatDateTime, formatNumber, serverUtc } from '@/format';
import { MappingTable } from '@/migration/MappingTable';
import { IssuesPanel } from '@/migration/IssuesPanel';
import { ENTITY_LABEL, ENTITY_ORDER } from '@/migration/schemas';
import s from '@/migration/migration.module.css';

/* ---------- 시작: 새 작업 + ID로 열기 (작업 목록 API 없음, gaps B9) ---------- */
function Start() {
  const navigate = useNavigate();
  const notify = useNotify();
  const create = useCreateMigration();
  const [name, setName] = useState('');
  const [cutover, setCutover] = useState<string | null>(null);
  const [openId, setOpenId] = useState<number | null>(null);
  const submit = () => {
    if (!name.trim()) return;
    create.mutate({ name: name.trim(), cutoverDate: cutover ?? undefined }, {
      onSuccess: (r) => { notify.success(`이관 작업 #${r.migrationId}을 만들었습니다`, '작업 ID를 메모해 두면 나중에 다시 열 수 있습니다'); navigate(`/admin/migration/${r.migrationId}`); },
      onError: (e) => notify.error(e, '작업을 만들지 못했습니다'),
    });
  };
  return (
    <div className={s.startGrid}>
      <Card title="새 이관 작업">
        <div className={s.form}>
          <Input placeholder="작업 이름 (예: 2026 인사 전환)" maxLength={100} value={name} onChange={(e) => setName(e.target.value)}
                 onPressEnter={submit} aria-label="작업 이름" />
          <div className={s.row}>
            <span className={s.muted}>전환 기준일</span>
            <DatePicker aria-label="전환 기준일" onChange={(v) => setCutover(v ? v.format('YYYY-MM-DD') : null)} />
          </div>
          <div className={s.row}>
            <span className={s.muted}>조직 → 사원 순서로 올립니다(조직을 먼저 적재해야 사원의 부서를 찾습니다).</span>
            <span className={s.spacer} />
            <Button type="primary" loading={create.isPending} disabled={!name.trim()} onClick={submit}>만들기</Button>
          </div>
        </div>
      </Card>
      <Card title="작업 ID로 열기">
        <div className={s.form}>
          <div className={s.row}>
            <InputNumber min={1} placeholder="작업 ID" value={openId} onChange={(v) => setOpenId(v)} aria-label="작업 ID"
                         onPressEnter={() => openId && navigate(`/admin/migration/${openId}`)} />
            <Button disabled={!openId} onClick={() => navigate(`/admin/migration/${openId}`)}>열기</Button>
          </div>
          <span className={s.muted}>작업 목록 API가 아직 없어 ID로 엽니다.</span>
        </div>
      </Card>
    </div>
  );
}

/** entity별 가장 최근 파일(같은 entity를 다시 올리면 파일이 하나 더 생긴다, gaps B9) */
function latestFile(d: MigrationDetail, entity: MigrationEntity): MigrationFileSummary | undefined {
  return d.files.filter((f) => f.entity === entity).sort((a, b) => b.fileId - a.fileId)[0];
}

/* ---------- ② 업로드 ---------- */
function UploadBox({ migrationId, entity, current }: { migrationId: number; entity: MigrationEntity; current?: MigrationFileSummary }) {
  const upload = useUploadMigrationFile(migrationId);
  const notify = useNotify();
  const [sheet, setSheet] = useState('');
  const [open, setOpen] = useState(!current);
  const send = (file: File) => {
    upload.mutate({ file, entity, sheet: sheet.trim() || undefined }, {
      onSuccess: (r) => {
        notify.success(`${file.name} ${formatNumber(r.rows)}행을 읽었습니다`,
          `시트 ${r.sheetName ?? '—'} · 헤더 ${r.headerRow}행 · 적재 불가 ${r.issueTotals.BLOCK} · 확인 필요 ${r.issueTotals.WARN} · 자동 정리 ${r.issueTotals.FIX}`);
        setOpen(false);
      },
      onError: (e) => notify.error(e, '파일을 읽지 못했습니다'),
    });
    return false;
  };
  return (
    <div className={s.section}>
      {current && (
        <div className={s.fileSum}>
          <b>{current.fileName}</b>
          <span className="nx-num">{formatNumber(current.rows)}행</span>
          <span className={s.muted}>올린 시각 {formatDateTime(serverUtc(current.uploadedAt), true)}</span>
          <span className={s.spacer} />
          {!open && <Button size="small" onClick={() => setOpen(true)}>다른 파일 올리기</Button>}
        </div>
      )}
      {open && (
        <>
          {current && <Alert type="warning" showIcon message="다시 올리면 새 파일로 추가됩니다"
                             description="이전 파일은 작업에서 지울 수 없어 남아 있습니다(파일 삭제 API 없음). 화면은 가장 최근 파일을 보여 줍니다." />}
          <Upload.Dragger accept=".xlsx,.csv" showUploadList={false} beforeUpload={send} disabled={upload.isPending}>
            <div className={s.drop}>
              <b>{upload.isPending ? '읽고 검증하는 중… (행이 많으면 1분 이상 걸릴 수 있습니다)' : `${ENTITY_LABEL[entity]} 파일(.xlsx, .csv)을 끌어 놓거나 눌러서 고르세요`}</b>
              <span className={s.muted}>20MB 이하. 시트를 비우면 필드가 가장 많이 맞는 시트를 고릅니다.</span>
            </div>
          </Upload.Dragger>
          <div className={s.row}>
            <span className={s.muted}>시트 이름(선택)</span>
            <Input style={{ width: 200 }} value={sheet} onChange={(e) => setSheet(e.target.value)} aria-label="시트 이름" />
            {current && <Button size="small" type="text" onClick={() => setOpen(false)}>닫기</Button>}
          </div>
        </>
      )}
    </div>
  );
}

/* ---------- entity 탭 하나: 업로드 → 매핑 → 검증 ---------- */
function EntityPanel({ d, entity }: { d: MigrationDetail; entity: MigrationEntity }) {
  const file = latestFile(d, entity);
  const detail = useMigrationFile(d.migrationId, file?.fileId ?? null);
  const mappedOk = !!detail.data && detail.data.missingRequired.length === 0 && detail.data.columnMappings.every((m) => m.confirmed);
  return (
    <div className={s.section}>
      <Card title="파일"><UploadBox key={file?.fileId ?? 'none'} migrationId={d.migrationId} entity={entity} current={file} /></Card>
      {file && (
        <>
          <Card title="매핑" meta={mappedOk ? <span className={s.muted}>확정됨</span> : <span className={s.pending}>확인 필요</span>}>
            <MappingTable migrationId={d.migrationId} fileId={file.fileId} entity={entity} />
          </Card>
          <Card title="검증·자동 정리">
            <IssuesPanel migrationId={d.migrationId} fileId={file.fileId} rows={file.rows} />
          </Card>
        </>
      )}
    </div>
  );
}

const STEPS = ['업로드', '매핑', '검증', '미리보기', '적재', '대사'];

function Job({ id }: { id: number }) {
  const q = useMigration(id);
  const [params, setParams] = useSearchParams();
  if (q.isError) return <ErrorPanel error={q.error} onRetry={() => q.refetch()} />;
  if (!q.data) return <Skeleton active={false} paragraph={{ rows: 8 }} />;
  const d = q.data;
  const ent = (ENTITY_ORDER as string[]).includes(params.get('entity') ?? '') ? (params.get('entity') as MigrationEntity) : 'HR_ORG';
  const hasAll = ENTITY_ORDER.every((e) => latestFile(d, e));
  const cur = hasAll ? 2 : d.files.length ? 1 : 0;
  return (
    <>
      <div className={s.head}>
        <h2 className={s.title}>{d.name}</h2>
        <span className="nx-mono">#{d.migrationId}</span>
        {d.cutoverDate && <span className={s.muted}>기준일 {formatDate(d.cutoverDate)}</span>}
        <StatusBadge domain="MigrationStatus" value={d.status} />
      </div>
      <ol className={s.stepper} aria-label="이관 단계">
        {STEPS.map((label, i) => (
          <li key={label} className={i < cur ? s.sDone : i === cur ? s.sCur : i >= 3 ? s.sLocked : undefined}>
            {'①②③④⑤⑥'[i]} {label}{i < cur && ' ✓'}
          </li>
        ))}
      </ol>
      <Alert type="info" showIcon style={{ marginBottom: 16 }}
             message="미리보기·적재·대사는 다음 단계에서 엽니다"
             description="적재 대상인 user-service 이관 API가 준비되면 연결합니다(frontend-backend-gaps.md B10)." />
      <Tabs
        activeKey={ent}
        onChange={(k) => setParams({ entity: k }, { replace: true })}
        items={ENTITY_ORDER.map((e) => {
          const f = latestFile(d, e);
          return { key: e, label: `${ENTITY_LABEL[e]}${f ? ` · ${f.fileName}` : ''}` };
        })}
      />
      <EntityPanel key={ent} d={d} entity={ent} />
    </>
  );
}

/** 데이터 이관(관리자, 설계 6.7) ①~③. 1280px 이상에서만 연다(설계 8.3 7.5) */
export function MigrationPage() {
  const { id } = useParams();
  const { band } = useViewport();
  const jobId = id && /^\d+$/.test(id) ? Number(id) : null;
  return (
    <>
      <PageHeader title="데이터 이관" actions={jobId ? <Link to="/admin/migration"><Button>새 작업·ID로 열기</Button></Link> : undefined} />
      {band !== 'wide'
        ? <Card><EmptyState title="1280px 이상 화면에서 열립니다" description="창을 넓히거나 브라우저 확대를 100%로 맞춰 주세요(Ctrl+0)." /></Card>
        : jobId ? <Job key={jobId} id={jobId} /> : <Start />}
    </>
  );
}
