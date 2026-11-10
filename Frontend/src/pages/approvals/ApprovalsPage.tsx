import { useRef, useState, type MutableRefObject } from 'react';
import { Button, Drawer, Select, Tabs } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useDocuments, type DocumentFilter, type DocumentStatus, type DocumentSummary, type FormType } from '@/api/approval';
import { PageHeader, EmptyState, Counter, Kbd } from '@/ui/primitives';
import { DataTable, applySearch, focusListSearch } from '@/ui/DataTable';
import { useHotkeys } from '@/common/useHotkeys';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { formTypeLabel } from '@/status/labels';
import { useViewport } from '@/shell/useViewport';
import { useTodoCounts } from '@/shell/useTodoCounts';
import { DocumentView } from '@/approval/DocumentView';
import { ComposeDrawer } from '@/approval/Compose';
import { compactDocColumns, docColumns } from '@/approval/columns';
import s from '@/approval/approval.module.css';

type Tab = 'pending' | 'drafted' | 'all';

const FORM_TYPES: FormType[] = ['GENERAL', 'BUSINESS_TRIP', 'LEAVE', 'OVERTIME', 'TIME_FIX', 'HOLIDAY_SUBST'];
const STATUSES: { value: DocumentStatus; label: string }[] = [
  { value: 'INPROGRESS', label: '진행중' }, { value: 'APPROVED', label: '승인' },
  { value: 'REJECTED', label: '반려' }, { value: 'CANCEL', label: '회수' },
];
const BOX: Record<Tab, DocumentFilter['box']> = { pending: 'PENDING', drafted: 'DRAFTED', all: undefined };
const EMPTY: Record<Tab, string> = {
  pending: '결재할 문서가 없습니다', drafted: '기안한 문서가 없습니다', all: '볼 수 있는 문서가 없습니다',
};

const docSearch = (r: DocumentSummary) => `${r.title} ${r.docNo}`;

function DocumentList({ tab, selected, onOpen, orderRef, narrow }:
  { tab: Tab; selected: number | null; onOpen: (id: number) => void; orderRef: MutableRefObject<number[]>; narrow: boolean }) {
  const [params, setParams] = useSearchParams();
  const formType = (params.get('form') as FormType | null) ?? undefined;
  // 결재 대기함은 진행중 문서만 나오므로 상태 필터를 두지 않는다
  const approvalStatus = tab === 'pending' ? undefined : (params.get('status') as DocumentStatus | null) ?? undefined;
  const q = useDocuments({ box: BOX[tab], formType, approvalStatus });
  // 화면에 보이는 순서(검색 반영). J/K 이동과 처리 후 다음 문서가 쓴다
  orderRef.current = applySearch(q.data, params.get('q') ?? '', docSearch).map((r) => r.documentId);

  const setParam = (k: string, v: string | undefined) => {
    const next = new URLSearchParams(params);
    if (v) next.set(k, v); else next.delete(k);
    setParams(next, { replace: true });
  };

  // 2분할 왼쪽(좁은 목록)은 칸 두 개로 줄여 상세 패널 쪽으로 넘치지 않게 한다. 상세를 Drawer로 여는 폭에서는 넓은 표 그대로
  const cols: ColumnsType<DocumentSummary> = narrow
    ? compactDocColumns({ drafter: tab !== 'drafted', status: tab !== 'pending' })
    : docColumns({ drafter: tab !== 'drafted', status: tab !== 'pending', progress: true });

  if (q.isError) return <ErrorPanel error={q.error} onRetry={() => q.refetch()} />;
  return (
    <>
      <div className={s.filters}>
        <Select allowClear placeholder="양식" style={{ width: 140 }} value={formType} aria-label="양식 필터"
                options={FORM_TYPES.map((f) => ({ value: f, label: formTypeLabel(f) }))}
                onChange={(v) => setParam('form', v)} />
        {tab !== 'pending' && (
          <Select allowClear placeholder="상태" style={{ width: 110 }} value={approvalStatus} aria-label="상태 필터"
                  options={STATUSES} onChange={(v) => setParam('status', v)} />
        )}
      </div>
      <DataTable<DocumentSummary>
        rowKey="documentId"
        tableLayout="fixed"
        data={q.data}
        loading={q.isPending}
        columns={cols}
        searchText={docSearch}
        searchPlaceholder="제목·문서번호 검색"
        emptyTitle={EMPTY[tab]}
        rowClassName={(r) => [s.row, r.documentId === selected ? s.rowActive : ''].join(' ')}
        onRow={(r) => ({
          onClick: () => onOpen(r.documentId),
          onKeyDown: (e) => { if (e.key === 'Enter') onOpen(r.documentId); },
          tabIndex: 0,
        })}
      />
      <div className={s.hint}>
        <Kbd>J</Kbd>/<Kbd>K</Kbd> 이동 · <Kbd>/</Kbd> 검색 · 상세에서 <Kbd>A</Kbd> 승인 · <Kbd>R</Kbd> 반려 · <Kbd>F</Kbd> 전결 · <Kbd>?</Kbd> 도움말
      </div>
    </>
  );
}

/** 전자결재 문서함 + 상세 2분할(설계 6.2). 1024~1151px에서는 상세를 Drawer로 연다. */
export function ApprovalsPage() {
  const { docId } = useParams();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const { band } = useViewport();
  const counts = useTodoCounts(false);
  const raw = params.get('tab');
  const tab: Tab = raw === 'drafted' || raw === 'all' ? raw : 'pending';
  const selected = docId && /^\d+$/.test(docId) ? Number(docId) : null;
  const [composing, setComposing] = useState(false);

  // 탭·필터 쿼리를 유지한 채 문서만 바꾼다
  const keep = (p: URLSearchParams) => (p.toString() ? `?${p.toString()}` : '');
  const open = (id: number) => navigate(`/approvals/${id}${keep(params)}`);
  const close = () => navigate(`/approvals${keep(params)}`);
  const switchTab = (k: string) => navigate(k === 'pending' ? '/approvals' : `/approvals?tab=${k}`);

  const orderRef = useRef<number[]>([]);
  // J/K: 보이는 목록에서 위아래 문서를 연다(2분할이라 선택이 곧 열기)
  const step = (d: 1 | -1) => {
    const ids = orderRef.current;
    if (ids.length === 0) return;
    const i = selected == null ? -1 : ids.indexOf(selected);
    const n = i < 0 ? (d > 0 ? 0 : ids.length - 1) : Math.min(ids.length - 1, Math.max(0, i + d));
    if (ids[n] !== selected) open(ids[n]);
  };
  useHotkeys({ j: () => step(1), k: () => step(-1), '/': focusListSearch });
  // 처리 후 다음 문서(마지막이면 이전 문서, 없으면 닫기). 목록은 아직 처리 전 순서다(재조회 전)
  const afterProcessed = (id: number) => {
    const ids = orderRef.current;
    const i = ids.indexOf(id);
    const next = i < 0 ? null : ids[i + 1] ?? ids[i - 1] ?? null;
    if (next != null) open(next); else close();
  };

  const list = <DocumentList key={tab} tab={tab} selected={selected} onOpen={open} orderRef={orderRef} narrow={band !== 'compact'} />;
  const detail = selected != null
    ? <DocumentView key={selected} id={selected} onProcessed={afterProcessed} />
    : <div className={s.empty}><EmptyState title="문서를 선택해 주세요" description="목록에서 문서를 고르면 여기에 상세가 표시됩니다" /></div>;

  return (
    <>
      <PageHeader title="전자결재" actions={<Button type="primary" onClick={() => setComposing(true)}>기안하기</Button>} />
      <ComposeDrawer
        open={composing}
        onClose={() => setComposing(false)}
        // 상신하면 기안함 탭에서 새 문서를 연다
        onCreated={(id) => { setComposing(false); navigate(`/approvals/${id}?tab=drafted`); }}
      />
      <Tabs
        activeKey={tab}
        onChange={switchTab}
        items={[
          {
            key: 'pending',
            label: <span className={s.tabLabel}>결재 대기{counts.approval !== undefined && <Counter value={counts.approval} label="결재 대기" />}</span>,
          },
          { key: 'drafted', label: '기안함' },
          { key: 'all', label: '전체' },
        ]}
      />
      {band === 'compact' ? (
        <>
          {list}
          <Drawer open={selected != null} onClose={close} width="70%" title={null} destroyOnHidden
                  styles={{ body: { padding: 0, display: 'flex' } }}>
            {selected != null && <DocumentView key={selected} id={selected} onProcessed={afterProcessed} />}
          </Drawer>
        </>
      ) : (
        <div className={s.split}>
          <div className={s.listPane}>{list}</div>
          <div className={s.detailPane}>{detail}</div>
        </div>
      )}
    </>
  );
}
