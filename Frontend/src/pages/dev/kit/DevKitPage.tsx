import { useLayoutEffect, useRef, useState } from 'react';
import { Button, Space, Table } from 'antd';
import { Background, ReactFlow, type Edge } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { statusMap, type StatusDomain } from '@/status/statusMap';
import { StatusBadge } from '@/ui/StatusBadge';
import { Icon } from '@/ui/Icon';
import * as Icons from '@/ui/icons';
import { AiMark, BarSegments, Card, Counter, DotChip, EmptyState, Kbd, PageHeader, modKey } from '@/ui/primitives';
import { ConfirmAction } from '@/ui/ConfirmAction';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { FeatureGate } from '@/ui/FeatureGate';
import { ApiError } from '@/api/ApiError';
import { nodeTypes, type FlowNodeType } from '@/flow/FlowNode';
import { formatDate, formatDateShort, formatDuration, formatDurationShort, formatMoney, nowKst } from '@/format';

/**
 * 1주차 /dev/kit (style-guide 12.6 완료 조건)
 * 1. statusMap 배지 전부와 icons.ts 아이콘 전부가 렌더된다
 * 2. 표 1개에서 행·머리 높이가 40±0.5px이다(아래 실측값)
 * 3. 960px 창 × 80%의 window.innerWidth를 실측한다(아래 표시)
 */
export function DevKitPage() {
  return (
    <>
      <PageHeader title="Dev Kit" description="1주차 검수 페이지 — 배지·아이콘·표 행 높이·뷰포트 실측" />
      <div style={{ display: 'grid', gap: 16 }}>
        <ViewportCard />
        <BadgesCard />
        <IconsCard />
        <TableCard />
        <MiscCard />
        <FlowCard />
      </div>
    </>
  );
}

function ViewportCard() {
  const [w, setW] = useState(window.innerWidth);
  useLayoutEffect(() => {
    const on = () => setW(window.innerWidth);
    window.addEventListener('resize', on);
    return () => window.removeEventListener('resize', on);
  }, []);
  const band = w >= 1280 ? '≥1280 (사이드바 232)' : w >= 1152 ? '1152~1279 (시연 반창, 2분할 유지)' : w >= 1024 ? '1024~1151 (Drawer)' : '<1024 (안내)';
  return (
    <Card title="3. 뷰포트 실측" meta="style-guide 5.4">
      <div className="nx-num">window.innerWidth = <b>{w}px</b> · devicePixelRatio = {window.devicePixelRatio} · 구간: {band}</div>
    </Card>
  );
}

function BadgesCard() {
  return (
    <Card title="1. StatusBadge 전부" meta="설계 4장 statusMap">
      <div style={{ display: 'grid', gap: 12 }}>
        {(Object.keys(statusMap) as StatusDomain[]).map((domain) => (
          <div key={domain} style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
            <span className="nx-mono" style={{ width: 200, color: 'var(--nx-n-600)' }}>{domain}</span>
            {Object.keys(statusMap[domain]).map((v) => <StatusBadge key={v} domain={domain} value={v} />)}
          </div>
        ))}
        <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
          <span className="nx-mono" style={{ width: 200, color: 'var(--nx-n-600)' }}>fallback</span>
          <StatusBadge domain="DocumentStatus" value="UNKNOWN_CODE" />
        </div>
      </div>
    </Card>
  );
}

function IconsCard() {
  const entries = Object.entries(Icons);
  return (
    <Card title={`1. icons.ts 아이콘 ${entries.length}개`} meta="lucide 1.5px absoluteStrokeWidth">
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(140px, 1fr))', gap: 8 }}>
        {entries.map(([name, C]) => (
          <div key={name} style={{ display: 'flex', alignItems: 'center', gap: 8, color: 'var(--nx-n-700)' }}>
            <Icon as={C} size={16} /><span style={{ fontSize: 12 }}>{name}</span>
          </div>
        ))}
      </div>
    </Card>
  );
}

interface Row { key: number; name: string; status: string; amount: number; date: string }
const rows: Row[] = [
  { key: 1, name: '경비 정산 — 점심 회의', status: 'INPROGRESS', amount: 128000, date: '2026-11-04' },
  { key: 2, name: '초과근무 신청 (2026-11-03)', status: 'APPROVED', amount: 0, date: '2026-11-03' },
  { key: 3, name: '출장 신청 — 부산', status: 'REJECTED', amount: 450000, date: '2025-12-30' },
];

function TableCard() {
  const ref = useRef<HTMLDivElement>(null);
  const [h, setH] = useState<{ head: number; row: number } | null>(null);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const measure = () => {
      const th = el.querySelector('thead tr') as HTMLElement | null;
      const tr = el.querySelector('tbody tr.ant-table-row') as HTMLElement | null;
      if (th && tr) setH({ head: th.getBoundingClientRect().height, row: tr.getBoundingClientRect().height });
    };
    measure();
    const t = setTimeout(measure, 300); // 폰트 로드 후 재측정
    return () => clearTimeout(t);
  }, []);
  const ok = (v: number) => Math.abs(v - 40) <= 0.5;
  return (
    <Card title="2. 표 행 높이 실측" meta={h ? `머리 ${h.head.toFixed(2)}px ${ok(h.head) ? '✓' : '✕'} · 행 ${h.row.toFixed(2)}px ${ok(h.row) ? '✓' : '✕'}` : '측정 중'} flush>
      <div ref={ref}>
        <Table<Row>
          dataSource={rows}
          pagination={false}
          size="middle"
          columns={[
            { title: '제목', dataIndex: 'name' },
            { title: '상태', dataIndex: 'status', render: (v: string) => <StatusBadge domain="DocumentStatus" value={v} /> },
            { title: '금액(원)', dataIndex: 'amount', align: 'right', render: (v: number) => <span className="nx-num">{v ? formatMoney(v).replace('원', '') : '—'}</span> },
            { title: '기안일', dataIndex: 'date', render: (v: string) => <span className="nx-num">{formatDateShort(v)}</span> },
          ]}
        />
      </div>
    </Card>
  );
}

function MiscCard() {
  return (
    <Card title="공통 컴포넌트" meta="Counter · Kbd · AiMark · DotChip · Bar · Empty · Error · Gate · Confirm">
      <div style={{ display: 'grid', gap: 16 }}>
        <Space wrap size={12}>
          <Counter value={0} /> <Counter value={3} /> <Counter value={1} tone="warning" />
          <Kbd>A</Kbd> <Kbd>{modKey}+Enter</Kbd>
          <Button type="primary">승인 <Kbd onPrimary>A</Kbd></Button>
          <AiMark label="AI 추출" /> <AiMark label="자동화로 생성됨" />
          <DotChip tone="success">업무</DotChip> <DotChip tone="outline">출근 전</DotChip> <DotChip tone="info">휴가</DotChip>
        </Space>
        <div className="nx-num" style={{ fontSize: 13, color: 'var(--nx-n-600)' }}>
          {formatDate(nowKst())} · {formatDuration(490)} · {formatDurationShort(490)} · {formatMoney(-32000)}
        </div>
        <BarSegments ariaLabel="이관 이슈" segments={[
          { value: 1, color: 'var(--nx-danger-fg)', label: '적재 불가 1' },
          { value: 37, color: 'var(--nx-warning-fg)', label: '확인 필요 37' },
          { value: 210, color: 'var(--nx-info-fg)', label: '자동 정리 210' },
        ]} />
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
          <ErrorPanel error={new ApiError(409, 'APPROVAL_024', '규정상 필수 결재자는 제외할 수 없습니다.', '3f1c2a9e-0d4b-4c1e-9a77-2b8e5f0c1d11')} onRetry={() => undefined} />
          <FeatureGate flag="leaveSubmit">{null}</FeatureGate>
        </div>
        <EmptyState title="결재할 문서가 없어요" description="새 문서가 오면 여기에 표시됩니다" action={<Button>결재함 전체 보기</Button>} />
        <div>
          <ConfirmAction title="배치 #3을 되돌립니다" confirmLabel="되돌리기" danger typeToConfirm="사원 이관 1차"
                         onConfirm={() => new Promise((r) => setTimeout(r, 600))}
                         trigger={(open) => <Button danger onClick={open}>배치 되돌리기</Button>}>
            HR_EMPLOYEE <b className="nx-num">1,252건</b>이 DB에서 삭제됩니다.
          </ConfirmAction>
        </div>
      </div>
    </Card>
  );
}

const demoNodes: FlowNodeType[] = [
  { id: 'n1', type: 'nexa', position: { x: 0, y: 0 }, data: { title: '파일 업로드', subtitle: '이미지', category: 'input', state: 'SUCCEEDED', elapsed: '0.1s' } },
  { id: 'n2', type: 'nexa', position: { x: 280, y: 0 }, data: { title: 'OCR 글자 인식', subtitle: '신뢰도 0.8', category: 'ai', state: 'SUCCEEDED', elapsed: '2.1s' } },
  { id: 'n3', type: 'nexa', position: { x: 560, y: 0 }, data: { title: '자동 검증', subtitle: 'GMS 교차', category: 'ai', state: 'RUNNING', elapsed: '3.2s' } },
  { id: 'n4', type: 'nexa', position: { x: 840, y: -60 }, data: { title: '사람 확인', subtitle: '불일치 검토', category: 'human', state: 'WAITING' } },
  { id: 'n5', type: 'nexa', position: { x: 840, y: 60 }, data: { title: '결재 상신', subtitle: '일반 기안', category: 'output', state: 'QUEUED' } },
];
const demoEdges: Edge[] = [
  { id: 'e1', source: 'n1', target: 'n2', type: 'smoothstep' },
  { id: 'e2', source: 'n2', target: 'n3', type: 'smoothstep' },
  { id: 'e3', source: 'n3', target: 'n4', type: 'smoothstep', label: '사람 확인' },
  { id: 'e4', source: 'n3', target: 'n5', type: 'smoothstep', label: '자동 확정' },
];

function FlowCard() {
  return (
    <Card title="FlowNode 시제품" meta="style-guide 8.13 — 빌더·실행 모니터 공용" flush>
      <div style={{ height: 260, background: 'var(--nx-n-25)' }}>
        <ReactFlow nodes={demoNodes} edges={demoEdges} nodeTypes={nodeTypes} fitView proOptions={{ hideAttribution: true }}
                   nodesDraggable={false} defaultEdgeOptions={{ style: { stroke: 'var(--nx-n-450)', strokeWidth: 1.5 } }}>
          <Background gap={16} size={1} color="var(--nx-n-200)" />
        </ReactFlow>
      </div>
    </Card>
  );
}
