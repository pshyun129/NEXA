import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { App, Button, Drawer, Input, InputNumber, Modal, Select, Skeleton } from 'antd';
import {
  Background, BackgroundVariant, MiniMap, ReactFlow, ReactFlowProvider, addEdge, applyEdgeChanges, applyNodeChanges, useReactFlow,
  type Connection, type Edge, type EdgeChange, type Node, type NodeChange,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import {
  fetchBuilderWorkflow, useBuilderWorkflow, useNodeCategories, useNodeDetails, useSaveGraph, useStartTestRun, useValidateGraph,
  type BuilderWorkflow, type Graph, type NodeDetail, type ValidationIssue,
} from '@/api/builder';
import { isFinal, runBadgeValue, useMyRuns, useRun, useRunSteps, type RunStep, type RunStatus } from '@/api/ai';
import { GRAPH_Y_SCALE, TONE_COLOR, lookOf, nodeTypes, type FlowNodeData, type NodeCategory, type NodeRunState } from '@/flow/FlowNode';
import { edgeTypes, type EdgeRunStatus, type FlowEdgeData } from '@/flow/FlowEdge';
import { Illustration } from '@/flow/Illustration';
import { RunMonitor } from '@/automation/RunMonitor';
import { SchemaForm, withFileField } from '@/automation/SchemaForm';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { StatusBadge } from '@/ui/StatusBadge';
import { Icon } from '@/ui/Icon';
import {
  Check, ChevronDown, ChevronRight, CircleAlert, GitBranch, Grip, History, Minus, Play, Plus, RotateCcw, Search, Sparkles,
  SquareArrowOutUpRight, TriangleAlert, Workflow, X, Expand, Link2, SlidersHorizontal, Receipt, FileText,
} from '@/ui/icons';
import { BlurTitle, Kbd, modKey } from '@/ui/primitives';
import { useNotify } from '@/ui/useNotify';
import { useHotkeys } from '@/common/useHotkeys';
import { formatDateTime, formatElapsed, formatTime, serverUtc } from '@/format';
import { orderWarnings } from './nodeIO';
import { DeployControls } from './DeployControls';
import { PlacementToggle } from './PlacementToggle';
import { InputFormEditor } from './InputFormEditor';
import s from './builder.module.css';

type BuilderNodeData = FlowNodeData & { nodeType: string; config: Record<string, unknown> };
type BNode = Node<BuilderNodeData, 'nexa'>;
type BEdge = Edge<FlowEdgeData, 'flow'>;

const PALETTE_SEARCH_ID = 'nx-builder-node-search';
/** 저장할 때는 다시 나눠 서버 그래프 좌표는 그대로 둔다(flow/FlowNode GRAPH_Y_SCALE) */
const Y_SCALE = GRAPH_Y_SCALE;

/**
 * 노드 부제(AI 노드는 FlowNode가 "AI · "를 붙인다).
 * 필수 설정 값은 값만("이미지", "영수증", "일반 기안"), 선택 설정은 "칸 이름 값"(예: "GMS 교차 검증 사용"),
 * 채운 설정이 없으면 노드 설명. 참조(ref) 칸은 {{…}} 문자열이라 쓰지 않는다.
 */
function subtitleOf(detail: NodeDetail | undefined, config: Record<string, unknown>) {
  const filled = (detail?.configFields ?? []).filter((c) => c.type !== 'ref' && String(config[c.key] ?? '').trim());
  const req = filled.filter((c) => c.required);
  if (req.length) return req.map((c) => String(config[c.key])).join(' · ');
  const opt = filled[0];
  if (opt) return `${opt.label.replace(/\s*\(.*\)\s*$/, '')} ${String(config[opt.key])}`;
  return detail?.description.split(/[(.]/)[0].trim() ?? '';
}

function toNode(n: Graph['nodes'][number], byType: Map<string, NodeDetail>): BNode {
  const d = byType.get(n.type);
  return {
    id: n.id, type: 'nexa', position: { x: n.position.x, y: n.position.y * Y_SCALE },
    data: {
      nodeType: n.type, config: n.config ?? {},
      title: d?.name ?? n.type,
      category: (d?.category ?? 'input') as NodeCategory,
      subtitle: subtitleOf(d, n.config ?? {}),
      branches: d?.branches.length ? d.branches : undefined,
    },
  };
}

function toEdge(e: Graph['edges'][number]): BEdge {
  return { id: e.id, source: e.source, target: e.target, sourceHandle: e.branch ?? undefined, type: 'flow', data: { branch: e.branch } };
}

function toGraph(nodes: BNode[], edges: BEdge[]): Graph {
  return {
    nodes: nodes.map((n) => ({
      id: n.id, type: n.data.nodeType,
      position: { x: Math.round(n.position.x), y: Math.round(n.position.y / Y_SCALE) },
      config: n.data.config,
    })),
    edges: edges.map((e) => ({ id: e.id, source: e.source, target: e.target, branch: e.data?.branch ?? null })),
  };
}

const nextId = (prefix: string, ids: string[]) =>
  `${prefix}${Math.max(0, ...ids.map((id) => Number(id.replace(/^\D+/, '')) || 0)) + 1}`;

/** 단계 출력 한 줄 요약(로그·설정 패널). 값이 긴 것은 자른다 */
function summarize(st: RunStep): string {
  if (st.error) return st.error;
  const waiting = (st.output as { waiting_for?: { message?: string } } | null)?.waiting_for;
  if (st.status === 'WAITING') return waiting?.message ?? '확인을 기다리고 있어요';
  if (st.status === 'RUNNING') return '작업을 처리하고 있어요…';
  const parts = Object.entries(st.output ?? {})
    .filter(([k, v]) => k !== 'waiting_for' && v != null && typeof v !== 'object')
    .slice(0, 3).map(([k, v]) => `${k} ${String(v)}`);
  const text = [st.branch ? `→ ${st.branch}` : '', ...parts].filter(Boolean).join(' · ');
  return text.length > 120 ? `${text.slice(0, 120)}…` : text || '완료';
}

/** 설정 패널: 서버 configFields로 칸을 만든다(요구사항 4.6). ref는 {{n3.fields}} 같은 참조 문자열이다 */
function ConfigForm({ detail, config, onChange }:
  { detail: NodeDetail; config: Record<string, unknown>; onChange: (key: string, v: unknown) => void }) {
  if (detail.configFields.length === 0) return <p className={s.muted}>설정할 항목이 없습니다.</p>;
  return (
    <div className={s.fields}>
      {detail.configFields.map((f) => {
        const v = config[f.key];
        const empty = v == null || String(v).trim() === '';
        return (
          <label key={f.key} className={s.field}>
            <span className={s.fieldLabel}>{f.label}{f.required && <span className={s.req}> *</span>}</span>
            {f.type === 'select' ? (
              <Select allowClear value={empty ? undefined : String(v)} status={f.required && empty ? 'warning' : undefined}
                      options={f.options.map((o) => ({ value: o, label: o }))} onChange={(x) => onChange(f.key, x ?? '')} />
            ) : f.type === 'number' ? (
              <InputNumber style={{ width: '100%' }} value={empty ? null : Number(v)} onChange={(x) => onChange(f.key, x ?? '')} />
            ) : (
              <Input value={empty ? '' : String(v)} status={f.required && empty ? 'warning' : undefined}
                     className={f.type === 'ref' ? 'nx-mono' : undefined}
                     placeholder={f.type === 'ref' ? '비우면 앞 노드의 같은 값' : undefined}
                     onChange={(e) => onChange(f.key, e.target.value)} />
            )}
          </label>
        );
      })}
    </div>
  );
}

const SUMMARY: Record<RunStatus | 'none', [string, string?]> = {
  none: ['실행할 준비가 됐어요.', '[테스트 실행]을 누르면 단계별 진행 상황이 여기에 표시되고, 캔버스에도 겹쳐 그려져요.'],
  QUEUED: ['실행을 준비하고 있어요.'],
  RUNNING: ['연결된 흐름을 따라 실행하고 있어요.'],
  WAITING: ['확인이 필요한 단계가 있어요.'],
  SUCCEEDED: ['모든 단계가 끝났어요.'],
  FAILED: ['실패한 단계가 있어요. 로그를 확인해 주세요.'],
  CANCELED: ['실행을 취소했어요.'],
};

/**
 * wf는 처음 받은 초안(캔버스 초기값), live는 최신 서버 값(배포 버전·노출 화면 등 헤더 표시용).
 * 캔버스는 다시 받은 값으로 덮지 않고, 헤더는 배포·노출 변경이 바로 보이게 live를 쓴다.
 */
function Editor({ wf, live, catalog, byType, onDirtyChange }:
  { wf: BuilderWorkflow; live: BuilderWorkflow; catalog: NodeDetail[]; byType: Map<string, NodeDetail>; onDirtyChange?: (dirty: boolean) => void }) {
  const notify = useNotify();
  const { modal } = App.useApp();
  const flow = useReactFlow<BNode, BEdge>();
  const categories = useNodeCategories();
  const save = useSaveGraph(wf.id);
  const validate = useValidateGraph(wf.id);
  const startTest = useStartTestRun(wf.id);

  const [nodes, setNodes] = useState<BNode[]>(() => wf.draftGraph.nodes.map((n) => toNode(n, byType)));
  const [edges, setEdges] = useState<BEdge[]>(() => wf.draftGraph.edges.map(toEdge));
  const [dirty, setDirty] = useState(false);
  // 이 화면에서 배포한 뒤 아직 바꾼 게 없으면 같은 초안을 또 배포하지 않게 막는다(서버는 같은 그래프도 새 버전으로 쌓는다)
  const [justDeployed, setJustDeployed] = useState(false);
  useEffect(() => { if (dirty) setJustDeployed(false); }, [dirty]);
  useEffect(() => { onDirtyChange?.(dirty); }, [dirty, onDirtyChange]);
  const [savedAt, setSavedAt] = useState(wf.updatedAt);
  const [issues, setIssues] = useState<ValidationIssue[] | null>(null);
  const [q, setQ] = useState('');
  const [libOpen, setLibOpen] = useState(false);
  const [view, setView] = useState<'editor' | 'history'>('editor');
  const [execTab, setExecTab] = useState<'logs' | 'issues'>('logs');
  /** 테스트 실행 창: form = 입력 폼, monitor = 실행 화면(사람 확인 포함) */
  const [drawer, setDrawer] = useState<'form' | 'monitor' | null>(null);
  const [testRunId, setTestRunId] = useState<number | null>(null);
  // 아래 실행 모니터는 접어 두고 캔버스를 넓게 쓴다. 테스트 실행·검사 이슈가 생기면 저절로 펼친다
  const [execOpen, setExecOpen] = useState(false);
  useEffect(() => { if (testRunId != null) setExecOpen(true); }, [testRunId]);
  useEffect(() => { if (execTab === 'issues') setExecOpen(true); }, [execTab]);

  const graph = useMemo(() => toGraph(nodes, edges), [nodes, edges]);
  const [formOpen, setFormOpen] = useState(false);
  // 파일 업로드 노드가 있는데 사원 입력 폼에 파일 칸이 없으면 사원이 파일을 올릴 수 없다(서버 검사는 이걸 보지 않는다)
  // 실행 첨부 파일(inputs.files)을 직접 읽는 노드: 파일 업로드, 그리고 'OCR할 파일'을 비운 OCR(FastAPI nodes/impl)
  const fileNode = graph.nodes.find((n) => n.type === 'file_upload'
    || (n.type === 'ocr_extract' && !String(n.config.files ?? '').trim()));
  const fileMissing = !!fileNode && !live.inputForm.some((f) => f.type === 'file');
  // 파일 칸이 없어도 실행 화면에는 '첨부 파일(필수)' 칸이 자동으로 붙고 서버가 첨부 없는 실행을 거절한다 → 경고가 아니라 안내
  const fileAccepts = useMemo(() => {
    const ups = graph.nodes.filter((n) => n.type === 'file_upload').map((n) => String(n.config.accept ?? '')).filter(Boolean);
    return ups.length ? [...new Set(ups)] : fileNode ? ['이미지'] : [];
  }, [graph, fileNode]);
  // 영수증 규정 검토가 있으면 테스트 실행 폼에도 영수증별 정보 표 (서버 effective_input_form과 같은 규칙)
  const receiptReview = graph.nodes.some((n) => n.type === 'policy_check' && String(n.config.doc_type ?? '영수증') === '영수증');
  const warnings = useMemo(() => orderWarnings(graph, catalog), [graph, catalog]);
  const selectedNode = nodes.find((n) => n.selected);
  const selectedEdge = edges.find((e) => e.selected);

  // 바뀌면 서버 검사 결과는 지난 그래프 기준이 되므로 지운다(클라이언트 경고는 늘 현재 그래프 기준)
  const touch = () => { setDirty(true); setIssues(null); };

  // 테스트 실행 상태를 캔버스에 겹쳐 그린다(같은 쿼리 키라 실행 화면과 폴링을 같이 쓴다)
  const testRun = useRun(testRunId ?? 0, false, testRunId != null);
  const testSteps = useRunSteps(testRunId ?? 0, testRun.data?.status, false, testRunId != null);
  const steps = testRunId != null ? testSteps.data ?? [] : [];
  const overlay = useMemo(() => {
    if (testRunId == null || !testSteps.data) return null;
    const m = new Map<string, { state: NodeRunState; elapsed?: string; branch: string | null; step: RunStep }>();
    for (const st of testSteps.data) {
      const ms = st.finishedAt ? Date.parse(serverUtc(st.finishedAt)) - Date.parse(serverUtc(st.startedAt)) : null;
      m.set(st.nodeId, { state: st.status, elapsed: ms != null ? formatElapsed(ms) : undefined, branch: st.branch, step: st });
    }
    return { steps: m, final: isFinal(testRun.data?.status) };
  }, [testRunId, testSteps.data, testRun.data?.status]);

  // 내 실행 기록(이 워크플로만). 서버는 최근 50건을 준다
  const myRuns = useMyRuns(null);
  const history = (myRuns.data ?? []).filter((r) => r.workflowId === wf.id);

  // 검사 표시와 실행 상태를 노드·선에 반영
  const errNodes = new Set(issues?.flatMap((i) => (i.nodeId ? [i.nodeId] : [])));
  const errEdges = new Set(issues?.flatMap((i) => (i.edgeId ? [i.edgeId] : [])));
  const warnNodes = new Set(warnings.map((w) => w.nodeId));
  const viewNodes = nodes.map((n) => {
    const issue = errNodes.has(n.id) ? 'error' as const : warnNodes.has(n.id) ? 'warning' as const : undefined;
    const run = overlay?.steps.get(n.id);
    const state: NodeRunState | undefined = run ? run.state : overlay?.final ? 'SKIPPED' : undefined;
    if (n.data.issue === issue && n.data.state === state && n.data.elapsed === run?.elapsed) return n;
    return { ...n, data: { ...n.data, issue, state, elapsed: run?.elapsed } };
  });
  const viewEdges = edges.map((e) => {
    let status: EdgeRunStatus = 'idle';
    if (errEdges.has(e.id)) status = 'error';
    else if (overlay) {
      const a = overlay.steps.get(e.source);
      const b = overlay.steps.get(e.target);
      // 분기 노드는 실제로 나간 분기의 선만 칠한다
      const taken = a?.state === 'SUCCEEDED' && (e.data?.branch == null || a.branch == null || e.data.branch === a.branch);
      if (taken && b) status = b.state === 'RUNNING' || b.state === 'WAITING' ? 'running' : 'success';
    }
    return e.data?.status === status ? e : { ...e, data: { branch: e.data?.branch ?? null, status } };
  });

  const onNodesChange = useCallback((changes: NodeChange<BNode>[]) => {
    if (changes.some((c) => c.type === 'position' || c.type === 'remove' || c.type === 'add')) touch();
    setNodes((ns) => applyNodeChanges(changes, ns));
  }, []);
  const onEdgesChange = useCallback((changes: EdgeChange<BEdge>[]) => {
    if (changes.some((c) => c.type === 'remove' || c.type === 'add')) touch();
    setEdges((es) => applyEdgeChanges(changes, es));
  }, []);
  const onConnect = useCallback((c: Connection) => {
    if (c.source === c.target) return;
    // 분기 노드는 출구 핸들 id가 분기 이름이다. 서버 그래프의 edge.branch로 그대로 저장한다
    const branch = c.sourceHandle ?? null;
    setEdges((es) => {
      if (es.some((e) => e.source === c.source && e.target === c.target && (e.data?.branch ?? null) === branch)) return es;
      const id = nextId('e', es.map((e) => e.id));
      return addEdge<BEdge>({ ...c, id, type: 'flow', data: { branch } }, es);
    });
    touch();
  }, []);

  const addNode = (type: string, position?: { x: number; y: number }) => {
    const d = byType.get(type);
    if (!d) return;
    const base = position ?? flow.screenToFlowPosition({ x: window.innerWidth / 2 - 107, y: window.innerHeight / 2 - 100 });
    setNodes((ns) => {
      // 화면 가운데에 이미 노드가 있으면 겹치지 않게 오른쪽 아래로 비켜 놓는다(QA AUTO-C2)
      const pos = { ...base };
      for (let i = 0; !position && i < 20 && ns.some((n) => Math.abs(n.position.x - pos.x) < 60 && Math.abs(n.position.y - pos.y) < 60); i++) {
        pos.x += 48; pos.y += 48;
      }
      return [
        ...ns.map((n) => ({ ...n, selected: false })),
        { ...toNode({ id: nextId('n', ns.map((n) => n.id)), type, position: { x: pos.x, y: pos.y / Y_SCALE }, config: {} }, byType), selected: true },
      ];
    });
    touch();
  };

  const setConfig = (id: string, key: string, v: unknown) => {
    setNodes((ns) => ns.map((n) => {
      if (n.id !== id) return n;
      const config = { ...n.data.config };
      if (v === '' || v == null) delete config[key]; else config[key] = v;
      return { ...n, data: { ...n.data, config, subtitle: subtitleOf(byType.get(n.data.nodeType), config) } };
    }));
    touch();
  };
  const setEdgeBranch = (id: string, branch: string) => {
    setEdges((es) => es.map((e) => (e.id === id ? { ...e, sourceHandle: branch, data: { ...e.data, branch } } : e)));
    touch();
  };

  // 마지막으로 불러오거나 저장한 서버 초안. 저장 전에 서버 초안이 이것과 다르면 다른 창에서 바뀐 것이다
  const syncedGraph = useRef(JSON.stringify(wf.draftGraph));
  const doSave = async () => {
    const server = await fetchBuilderWorkflow(wf.id);
    if (JSON.stringify(server.draftGraph) !== syncedGraph.current) {
      // 검사·테스트 실행 전 자동 저장이 다른 창의 변경을 말없이 덮어쓰던 문제(QA AUTO-C2) → 먼저 묻는다
      const overwrite = await new Promise<boolean>((resolve) => modal.confirm({
        title: '다른 창에서 이 초안이 바뀌었습니다',
        content: `서버 초안이 ${formatTime(serverUtc(server.updatedAt))}에 저장됐습니다. 지금 화면의 그래프로 덮어쓸까요?`,
        okText: '덮어쓰기', cancelText: '취소', okButtonProps: { danger: true }, zIndex: 1200,
        onOk: () => resolve(true), onCancel: () => resolve(false),
      }));
      if (!overwrite) throw new Error('저장을 취소했습니다');
    }
    const wf2 = await save.mutateAsync(graph);
    syncedGraph.current = JSON.stringify(wf2.draftGraph);
    setDirty(false);
    setSavedAt(wf2.updatedAt);
    return wf2;
  };
  const onSave = () => doSave().then(() => notify.success('초안을 저장했습니다')).catch((e) => notify.error(e, '저장하지 못했습니다'));
  // 서버 검사·테스트 실행은 저장된 초안을 본다 → 저장 안 한 변경이 있으면 먼저 저장한다
  const onValidate = async () => {
    try {
      if (dirty) await doSave();
      const r = await validate.mutateAsync();
      setIssues(r.issues);
      if (r.issues.length || warnings.length) setExecTab('issues');
      if (r.valid && warnings.length === 0) notify.success('검사를 통과했습니다');
    } catch (e) { notify.error(e, '검사하지 못했습니다'); }
  };
  const onTest = async () => {
    try {
      if (dirty) await doSave();
      setDrawer('form');
    } catch (e) { notify.error(e, '저장하지 못해 테스트 실행을 열 수 없습니다'); }
  };

  // '/' 노드 검색(설계 6.6). 단계 추가 창을 열고 검색 칸에 맞춘다. 입력 중·모달 안에서는 useHotkeys가 무시한다
  useHotkeys({ '/': () => setLibOpen(true) });

  // Ctrl+S 저장, Ctrl+Shift+V 검사(설계 6.6). 입력 중에도 동작해야 하므로 useHotkeys(단일 키 전용)를 쓰지 않는다
  const keysRef = useRef({ onSave, onValidate });
  keysRef.current = { onSave, onValidate };
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!(e.ctrlKey || e.metaKey) || document.querySelector('.ant-drawer-open')) return;
      const k = e.key.toLowerCase();
      if (k === 's' && !e.shiftKey) { e.preventDefault(); keysRef.current.onSave(); }
      if (k === 'v' && e.shiftKey) { e.preventDefault(); keysRef.current.onValidate(); }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  // 저장 안 한 변경이 있으면 새로고침·창 닫기와 화면 안 링크 이동을 확인한다(BrowserRouter라 useBlocker를 쓸 수 없다)
  useEffect(() => {
    if (!dirty) return;
    const onUnload = (e: BeforeUnloadEvent) => { e.preventDefault(); e.returnValue = ''; };
    const onClick = (e: MouseEvent) => {
      const a = (e.target as HTMLElement | null)?.closest('a[href]');
      if (!a || a.getAttribute('target') === '_blank') return;
      if (!window.confirm('저장하지 않은 변경이 있습니다. 이동할까요?')) { e.preventDefault(); e.stopPropagation(); }
    };
    window.addEventListener('beforeunload', onUnload);
    document.addEventListener('click', onClick, true);
    return () => { window.removeEventListener('beforeunload', onUnload); document.removeEventListener('click', onClick, true); };
  }, [dirty]);

  const fit = () => flow.fitView({ padding: 0.14, maxZoom: 1, duration: 300 });
  const focusNode = (id: string) => {
    setView('editor');
    setNodes((ns) => ns.map((n) => ({ ...n, selected: n.id === id })));
    setEdges((es) => es.map((e) => ({ ...e, selected: false })));
    flow.fitView({ nodes: [{ id }], duration: 300, maxZoom: 1 });
  };
  const focusEdge = (id: string) => {
    const e = edges.find((x) => x.id === id);
    if (!e) return;
    setEdges((es) => es.map((x) => ({ ...x, selected: x.id === id })));
    setNodes((ns) => ns.map((n) => ({ ...n, selected: false })));
    flow.fitView({ nodes: [{ id: e.source }, { id: e.target }], duration: 300, maxZoom: 1 });
  };
  const clearSelection = () => {
    setNodes((ns) => ns.map((n) => (n.selected ? { ...n, selected: false } : n)));
    setEdges((es) => es.map((e) => (e.selected ? { ...e, selected: false } : e)));
  };
  const nodeName = (id: string | null) => {
    const n = nodes.find((x) => x.id === id);
    return n ? `'${n.data.title}'` : id ?? '';
  };

  const palette = useMemo(() => {
    const needle = q.trim();
    const list = needle ? catalog.filter((n) => n.name.includes(needle) || n.description.includes(needle)) : catalog;
    return (categories.data ?? []).map((c) => ({ ...c, items: list.filter((n) => n.category === c.code) }))
      .filter((g) => g.items.length > 0);
  }, [catalog, categories.data, q]);

  const issueCount = (issues?.length ?? 0) + warnings.length;
  const selDetail = selectedNode ? byType.get(selectedNode.data.nodeType) : undefined;
  const selLook = selectedNode ? lookOf(selectedNode.data.nodeType, selectedNode.data.category) : undefined;
  const selRun = selectedNode ? overlay?.steps.get(selectedNode.id) : undefined;
  const selEdgeSource = selectedEdge ? nodes.find((n) => n.id === selectedEdge.source) : undefined;
  const selEdgeBranches = selEdgeSource?.data.branches ?? [];

  // 실행 모니터 요약
  const run = testRunId != null ? testRun.data : undefined;
  const done = steps.filter((x) => x.status === 'SUCCEEDED').length;
  const runStart = run ? Date.parse(serverUtc(run.startedAt ?? run.createdAt)) : 0;
  const lastEnd = steps.reduce((t, x) => Math.max(t, x.finishedAt ? Date.parse(serverUtc(x.finishedAt)) : 0), 0);
  const secs = run && lastEnd ? Math.max(0, (lastEnd - runStart) / 1000) : 0;
  const pct = run ? (run.status === 'SUCCEEDED' ? 100 : Math.round((done / Math.max(1, nodes.length)) * 100)) : 0;
  const [sumTitle, sumSub] = SUMMARY[run?.status ?? 'none'];
  const indCls = !run ? '' : run.status === 'SUCCEEDED' ? s.indOk : run.status === 'FAILED' ? s.indErr
    : run.status === 'WAITING' ? s.indWait : run.status === 'CANCELED' ? '' : s.indRun;

  return (
    <div className={s.editor}>
      {/* ---------- 제목줄(한 줄): 이름 · 보기 탭 · 도구 ---------- */}
      <section className={s.titlebar}>
        <div className={s.titleText}>
          <div className={s.headingLine}>
            <BlurTitle key={wf.name} text={wf.name} className={s.name} />
            <span className={live.deployedVersion != null ? s.badgeOn : s.badge}>
              {live.deployedVersion != null ? `v${live.deployedVersion} 배포됨` : '배포 전'}
            </span>
            <span className={dirty ? s.unsaved : s.saved}><i />{dirty ? '저장 안 됨' : `저장 ${formatTime(serverUtc(savedAt))}`}</span>
          </div>
          <p className={s.desc}>{live.description || '노드를 연결해 흐름을 만들고, 검사한 뒤 테스트 실행합니다.'}</p>
        </div>
        <div className={s.tabs} role="tablist" aria-label="빌더 보기">
          <button type="button" role="tab" aria-selected={view === 'editor'} className={view === 'editor' ? s.tabOn : ''} onClick={() => setView('editor')}>
            <Icon as={Workflow} size={16} />캔버스
          </button>
          <button type="button" role="tab" aria-selected={view === 'history'} className={view === 'history' ? s.tabOn : ''} onClick={() => setView('history')}>
            <Icon as={History} size={16} />실행 기록<span>{myRuns.data ? history.length : '–'}</span>
          </button>
        </div>
        <div className={s.titleActions}>
          <Button icon={<Icon as={Check} size={16} />} onClick={onSave} loading={save.isPending} disabled={!dirty} title={`${modKey}+S`}>저장</Button>
          <Button onClick={onValidate} loading={validate.isPending} title={`${modKey}+Shift+V`}>검사</Button>
          <InputFormEditor workflowId={wf.id} fields={live.inputForm} open={formOpen} setOpen={setFormOpen} needsFile={!!fileNode} />
          <PlacementToggle workflowId={wf.id} placements={live.placements} deployed={live.deployedVersion != null} />
          <DeployControls
            workflowId={wf.id}
            warningCount={warnings.length}
            exposed={live.placements.includes('HOME')}
            fileMissing={fileMissing}
            onDeployed={() => setJustDeployed(true)}
            blockedReason={dirty ? '저장한 뒤 검사를 통과해야 배포할 수 있습니다'
              : justDeployed ? `마지막 배포(v${live.deployedVersion}) 이후 바뀐 내용이 없습니다`
              : issues === null ? '[검사]를 먼저 실행해 주세요'
              : issues.length > 0 ? `검사 이슈 ${issues.length}건을 먼저 해결해 주세요`
              : null}
          />
          <Button type="primary" className={s.runBtn} icon={<Icon as={Play} size={12} />} onClick={onTest}>테스트 실행</Button>
        </div>
      </section>

      <div className={s.studioBody}>
        <section className={s.canvasColumn}>
          {view === 'editor' ? (
            <div className={s.canvas}>
              <div className={s.canvasHead}>
                <div className={s.canvasLabel}>
                  <span className={s.symbol}><Icon as={GitBranch} size={16} /></span>
                  <div>
                    <strong>WORKFLOW</strong>
                    <span>{nodes.length}개의 단계 <i /> 연결선 {edges.length}개</span>
                  </div>
                </div>
                <button type="button" className={s.addBtn} onClick={() => setLibOpen(true)}><Icon as={Plus} size={16} />단계 추가</button>
              </div>
              <ReactFlow<BNode, BEdge>
                nodes={viewNodes} edges={viewEdges} nodeTypes={nodeTypes} edgeTypes={edgeTypes}
                onNodesChange={onNodesChange} onEdgesChange={onEdgesChange} onConnect={onConnect}
                deleteKeyCode={['Delete', 'Backspace']} fitView fitViewOptions={{ padding: 0.14, maxZoom: 1 }} minZoom={0.2} maxZoom={1.6}
                proOptions={{ hideAttribution: true }} defaultEdgeOptions={{ type: 'flow' }}
                // 핸들 근처(48px)에서 놓아도 이어지고, 핸들을 눌렀다 다른 핸들을 눌러도 이어진다
                connectionRadius={48} connectOnClick
                connectionLineStyle={{ stroke: '#8398cc', strokeWidth: 2.5 }}
              >
                <Background variant={BackgroundVariant.Dots} gap={20} size={1} color="#d9dce2" />
                <MiniMap pannable zoomable className={s.minimap} maskColor="rgba(249,250,251,.85)"
                         nodeColor={(n) => TONE_COLOR[lookOf((n.data as BuilderNodeData).nodeType, (n.data as BuilderNodeData).category).tone]} />
              </ReactFlow>
              <div className={s.canvasBottom}>
                <div className={s.zoom}>
                  <button type="button" aria-label="확대" onClick={() => flow.zoomIn({ duration: 200 })}><Icon as={Plus} size={16} /></button>
                  <button type="button" aria-label="축소" onClick={() => flow.zoomOut({ duration: 200 })}><Icon as={Minus} size={16} /></button>
                  <span />
                  <button type="button" aria-label="전체 화면에 맞추기" onClick={fit}><Icon as={Expand} size={16} /></button>
                </div>
                <span className={s.canvasHint}><Icon as={Grip} size={12} />노드 오른쪽 점을 끌어 다른 노드 왼쪽 점 근처에 놓으면 이어져요 · 선택 후 <Kbd>Delete</Kbd> 삭제</span>
              </div>
              {!overlay && <div className={s.caption}><span />한 번 이어 두면, 매번 같은 흐름으로.</div>}
            </div>
          ) : (
            <div className={s.historyView}>
              <div className={s.eyebrow}>EXECUTION HISTORY</div>
              <h2>흐름이 남긴 기록</h2>
              <p>내가 이 워크플로를 실행한 기록입니다(테스트 실행 포함, 최근 50건 중). 누르면 캔버스에 겹쳐 봅니다.</p>
              {myRuns.isError ? <ErrorPanel error={myRuns.error} onRetry={() => myRuns.refetch()} />
                : !myRuns.data ? <Skeleton active={false} paragraph={{ rows: 3 }} />
                : history.length === 0 ? (
                  <div className={s.emptyHistory}>
                    <Icon as={History} size={24} />
                    <strong>아직 실행 기록이 없어요</strong>
                    <p>테스트 실행으로 첫 번째 흐름을 확인해 보세요.</p>
                    <Button type="primary" icon={<Icon as={Play} size={12} />} onClick={onTest}>첫 테스트 실행</Button>
                  </div>
                ) : history.map((r) => (
                  <button key={r.id} type="button" className={s.historyRow} onClick={() => { setTestRunId(r.id); setView('editor'); setExecTab('logs'); }}>
                    <span className={s.historyNo}>{r.id}</span>
                    <span className={s.historyMain}>
                      <strong>{r.isTest ? '테스트 실행' : '실행'} · run #{r.id}{r.version != null && ` · v${r.version}`}</strong>
                      <small>{formatDateTime(serverUtc(r.createdAt), true)}
                        {r.startedAt && r.finishedAt && ` · ${formatElapsed(Date.parse(serverUtc(r.finishedAt)) - Date.parse(serverUtc(r.startedAt)))}`}</small>
                    </span>
                    <StatusBadge domain="RunStatus" value={runBadgeValue(r)} />
                  </button>
                ))}
            </div>
          )}

          {/* ---------- 실행 모니터 / 검사 결과 ---------- */}
          <section className={`${s.exec} ${execOpen ? '' : s.execClosed}`} aria-label="실행 모니터">
            <div className={s.execHead}>
              <div className={s.execTitle}>
                <span className={`${s.ind} ${indCls}`} />
                <div className={s.execTabs} role="tablist" aria-label="아래 패널">
                  <button type="button" role="tab" aria-selected={execTab === 'logs'} className={execTab === 'logs' ? s.execTabOn : ''} onClick={() => setExecTab('logs')}>실행 모니터</button>
                  <button type="button" role="tab" aria-selected={execTab === 'issues'} className={execTab === 'issues' ? s.execTabOn : ''} onClick={() => setExecTab('issues')}>
                    검사 결과<span className={issueCount ? s.countWarn : s.count}>{issueCount}</span>
                  </button>
                </div>
                <span className={s.runId}>{testRunId != null ? `RUN #${String(testRunId).padStart(3, '0')}${run?.isTest ? ' · 초안 테스트' : ''}` : 'LIVE PREVIEW'}</span>
                {!execOpen && run && <span className={s.execPeek}>{sumTitle} · {done}/{nodes.length} 단계</span>}
              </div>
              <div className={s.execActions}>
                <button type="button" className={s.iconBtn} aria-expanded={execOpen} aria-label={execOpen ? '실행 모니터 접기' : '실행 모니터 펼치기'}
                        onClick={() => setExecOpen((o) => !o)}>
                  <Icon as={ChevronDown} size={16} className={execOpen ? '' : s.chevUp} />
                </button>
                {testRunId != null && (
                  <>
                    <Button size="small" type={run?.status === 'WAITING' ? 'primary' : 'default'} icon={<Icon as={SquareArrowOutUpRight} size={12} />}
                            onClick={() => setDrawer('monitor')}>{run?.status === 'WAITING' ? '확인하러 가기' : '실행 화면'}</Button>
                    <button type="button" className={s.iconBtn} aria-label="실행 표시 지우기" onClick={() => setTestRunId(null)}><Icon as={RotateCcw} size={16} /></button>
                  </>
                )}
              </div>
            </div>
            {execTab === 'logs' ? (
              <>
                <div className={s.execSummary}>
                  <div>
                    <strong>{sumTitle}</strong>
                    <span>{run ? `${done}/${nodes.length} 단계 완료 · ${secs.toFixed(1)}초` : sumSub}</span>
                  </div>
                  <div className={s.progress} role="progressbar" aria-label="실행 진행도" aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct}>
                    <span style={{ width: `${pct}%` }} className={run?.status === 'FAILED' ? s.progressFail : ''} />
                  </div>
                  <span className={s.pct}>{pct}%</span>
                </div>
                <div className={s.logList} aria-live="polite">
                  {testRun.isError ? <ErrorPanel error={testRun.error} onRetry={() => testRun.refetch()} />
                    : steps.length === 0 ? (
                      <div className={s.logEmpty}>
                        <span className={s.terminal}>›_</span>
                        <span>{testRunId != null ? '첫 단계를 준비하고 있어요…' : '반복되는 일은 흐름에 맡기고, 중요한 일에 집중하세요.'}</span>
                        <span className={s.waitingPill}>{testRunId != null ? '준비 중' : '대기 중'}</span>
                      </div>
                    ) : steps.map((st) => (
                      <button key={st.id} type="button" className={`${s.logLine} ${s[`log-${st.status}`] ?? ''}`} onClick={() => focusNode(st.nodeId)}>
                        <span className={s.logTime}>+{runStart ? Math.max(0, (Date.parse(serverUtc(st.startedAt)) - runStart) / 1000).toFixed(1) : '0.0'}s</span>
                        <span className={s.logIcon}>
                          {st.status === 'RUNNING' ? <i className={s.spin} />
                            : st.status === 'FAILED' || st.status === 'WAITING' ? <Icon as={CircleAlert} size={16} />
                            : <Icon as={Check} size={16} />}
                        </span>
                        <strong>{byType.get(st.nodeType)?.name ?? st.nodeType}</strong>
                        <span className={s.logOut}>{summarize(st)}</span>
                        <Icon as={ChevronRight} size={12} />
                      </button>
                    ))}
                </div>
              </>
            ) : (
              <div className={s.logList}>
                {issues === null && warnings.length === 0 && (
                  <div className={s.logEmpty}><span className={s.terminal}>✓</span>
                    <span>{dirty ? '변경 후 아직 서버 검사를 하지 않았습니다.' : '[검사]로 서버 검사를 실행합니다.'}</span></div>
                )}
                {issues !== null && issueCount === 0 && (
                  <div className={s.logEmpty}><span className={s.terminal}>✓</span><span>문제없이 이어져 있어요.</span><span className={s.waitingPill}>통과</span></div>
                )}
                {issues?.map((i) => (
                  <div key={`${i.nodeId}-${i.edgeId}-${i.message}`} className={s.issue}>
                    <Icon as={CircleAlert} size={16} className={s.errIcon} />
                    <span className={s.issueText}>{i.nodeId ? `${nodeName(i.nodeId)}: ` : i.edgeId ? '연결선: ' : '그래프 전체: '}{i.message}</span>
                    {i.nodeId && <Button size="small" type="link" onClick={() => focusNode(i.nodeId!)}>노드로 이동</Button>}
                    {!i.nodeId && i.edgeId && <Button size="small" type="link" onClick={() => focusEdge(i.edgeId!)}>선으로 이동</Button>}
                  </div>
                ))}
                {warnings.map((w) => (
                  <div key={`${w.nodeId}-${w.message}`} className={s.issue}>
                    <Icon as={TriangleAlert} size={16} className={s.warnIcon} />
                    <span className={s.issueText}>{w.message}</span>
                    <Button size="small" type="link" onClick={() => focusNode(w.nodeId)}>노드로 이동</Button>
                  </div>
                ))}
              </div>
            )}
          </section>
        </section>

        {/* ---------- 인스펙터 ---------- */}
        <aside className={s.inspector} aria-label="설정">
          <div className={s.inspectorHead}>
            <span>{selectedNode ? '단계 설정' : selectedEdge ? '연결선' : '워크플로 안내'}</span>
            {selectedNode || selectedEdge
              ? <button type="button" className={s.iconBtn} aria-label="선택 해제" onClick={clearSelection}><Icon as={X} size={16} /></button>
              : <Icon as={SlidersHorizontal} size={16} />}
          </div>
          <div className={s.inspectorBody}>
            {selectedNode && selDetail && selLook ? (
              <>
                <div className={s.insArt} style={{ color: TONE_COLOR[selLook.tone], background: `color-mix(in srgb, ${TONE_COLOR[selLook.tone]} 9%, white)` }}>
                  <Illustration kind={selLook.art} className={s.insIll} />
                  <span>{selDetail.category.toUpperCase()}</span>
                </div>
                <div className={s.insIntro}>
                  <h2>{selDetail.name}</h2>
                  <p>{selDetail.description}</p>
                </div>
                <div className={s.insSection}>
                  <div className={s.fld}>노드 타입<div><code>{selDetail.nodeType}</code> <span className={s.muted}>({selectedNode.id})</span></div></div>
                  <ConfigForm detail={selDetail} config={selectedNode.data.config} onChange={(k, v) => setConfig(selectedNode.id, k, v)} />
                  {selDetail.outputs.length > 0 && (
                    <div className={s.keys}>출력 키 {selDetail.outputs.map((o) => <code key={o.key} title={o.label}>{o.key}</code>)}</div>
                  )}
                  {selDetail.branches.length > 0 && <div className={s.keys}>분기 {selDetail.branches.map((b) => <code key={b}>{b}</code>)}</div>}
                  {warnings.filter((w) => w.nodeId === selectedNode.id).map((w) => (
                    <p key={w.message} className={s.warnText}><Icon as={TriangleAlert} size={16} /> {w.message}</p>
                  ))}
                </div>
                <div className={s.insSection}>
                  <h3>실행 상태 <span className={`${s.statusLabel} ${selRun ? s[`sl-${selRun.state}`] ?? '' : ''}`}>
                    {selRun ? ({ RUNNING: '실행 중', WAITING: '확인 대기', SUCCEEDED: '완료', FAILED: '실행 실패' } as Record<string, string>)[selRun.state] ?? selRun.state
                      : overlay?.final ? '실행 안 됨' : '실행 대기'}
                  </span></h3>
                  <div className={s.outputBox}>
                    <span>OUTPUT</span>
                    <p>{selRun ? summarize(selRun.step) : '테스트 실행에서 이 단계가 돌면 결과가 여기에 표시돼요.'}</p>
                  </div>
                </div>
                <div className={s.insSection}>
                  <button type="button" className={s.deleteBtn} onClick={() => flow.deleteElements({ nodes: [{ id: selectedNode.id }] })}>이 단계 삭제</button>
                </div>
              </>
            ) : selectedEdge ? (
              <div className={s.insSection}>
                <p className={s.muted}>{nodeName(selectedEdge.source)} → {nodeName(selectedEdge.target)}</p>
                {selEdgeBranches.length > 0 && (
                  <label className={s.field}>
                    <span className={s.fieldLabel}>분기 결과<span className={s.req}> *</span></span>
                    <Select value={selectedEdge.data?.branch ?? undefined}
                            status={selectedEdge.data?.branch && selEdgeBranches.includes(selectedEdge.data.branch) ? undefined : 'error'}
                            options={selEdgeBranches.map((b) => ({ value: b, label: b }))}
                            onChange={(b) => setEdgeBranch(selectedEdge.id, b)} />
                  </label>
                )}
                <button type="button" className={s.deleteBtn} onClick={() => flow.deleteElements({ edges: [{ id: selectedEdge.id }] })}>이 선 삭제</button>
              </div>
            ) : (
              <>
                <div className={s.introArt} aria-hidden>
                  <div className={s.orbitOne} /><div className={s.orbitTwo} />
                  <div className={`${s.tile} ${s.tileOne}`}><Icon as={Receipt} size={24} /></div>
                  <div className={`${s.tile} ${s.tileTwo}`}><Icon as={FileText} size={24} /></div>
                  <div className={`${s.tile} ${s.tileThree}`}><Icon as={Check} size={24} /></div>
                  <span className={s.spark}><Icon as={Sparkles} size={20} /></span>
                </div>
                <div className={s.insIntro}>
                  <div className={s.eyebrow}>THOUGHTFULLY CONNECTED</div>
                  <h2>작은 연결이 만드는<br />반복 없는 하루.</h2>
                  <p>노드를 끌어 잇고 검사한 뒤, 테스트 실행으로 흐름을 확인하세요.</p>
                </div>
                <div className={`${s.insSection} ${s.facts}`}>
                  <div><span>단계</span><strong className="nx-num">{nodes.length}개</strong></div>
                  <div><span>배포 버전</span><strong>{live.deployedVersion != null ? `v${live.deployedVersion}` : '없음'}</strong></div>
                  <div><span>노출 화면</span><strong>{live.placements.length ? live.placements.join(' · ') : '없음'}</strong></div>
                  <div><span>사원 입력 폼</span><strong className="nx-num">{live.inputForm.length}칸</strong></div>
                </div>
                <div className={s.insSection}>
                  <h3>이렇게 이어집니다</h3>
                  <div className={s.journey}>
                    <div><span>01</span><p><strong>단계를 놓고 잇고</strong><small>[단계 추가]로 노드를 놓고 연결점을 끌어 이어요.</small></p></div>
                    <div><span>02</span><p><strong>검사하고</strong><small>[검사]로 빠진 값과 끊긴 연결을 찾아요.</small></p></div>
                    <div><span>03</span><p><strong>테스트 실행</strong><small>실행 상태가 캔버스 위에 그대로 그려져요.</small></p></div>
                  </div>
                </div>
                <div className={s.tip}><Icon as={Sparkles} size={16} /><p>노드를 클릭해 보세요.<span>세부 설정과 실행 결과를 볼 수 있어요.</span></p></div>
              </>
            )}
          </div>
          <div className={s.inspectorFoot}><Icon as={Link2} size={12} /><span>저장한 초안 기준 · 테스트 실행은 초안 그래프로 돌아요</span></div>
        </aside>
      </div>

      {/* ---------- 단계 추가(라이브러리) ---------- */}
      <Modal open={libOpen} onCancel={() => setLibOpen(false)} footer={null} width={640} destroyOnHidden className={s.libModal}
             // 다시 열면 지난 검색어 없이 전체 목록부터(QA AUTO-C2)
             afterOpenChange={(o) => { if (o) document.getElementById(PALETTE_SEARCH_ID)?.focus(); else setQ(''); }}>
        <div className={s.libHead}>
          <div className={s.eyebrow}>BUILD YOUR FLOW</div>
          <h2>다음 연결을 선택하세요.</h2>
        </div>
        <Input id={PALETTE_SEARCH_ID} size="large" allowClear prefix={<Icon as={Search} size={16} />} placeholder="기능 검색"
               aria-label="노드 검색" value={q} onChange={(e) => setQ(e.target.value)} />
        <div className={s.libBody}>
          {palette.length === 0 && <p className={s.muted}>찾는 노드가 없습니다.</p>}
          {palette.map((g) => (
            <section key={g.code}>
              <h3 className={s.libGroup}>{g.name}</h3>
              <div className={s.libGrid}>
                {g.items.map((n) => {
                  const look = lookOf(n.nodeType, n.category as NodeCategory);
                  const color = TONE_COLOR[look.tone];
                  return (
                    <button key={n.nodeType} type="button" className={s.libCard}
                            style={{ color, background: `color-mix(in srgb, ${color} 7%, white)`, borderColor: `color-mix(in srgb, ${color} 20%, white)` }}
                            onClick={() => { addNode(n.nodeType); setLibOpen(false); }}>
                      <Illustration kind={look.art} className={s.libIll} />
                      <strong>{n.name}</strong>
                      <span>{n.description}</span>
                      <Icon as={Plus} size={16} className={s.libPlus} />
                    </button>
                  );
                })}
              </div>
            </section>
          ))}
        </div>
        <p className={s.libNote}>추가한 노드의 연결점을 드래그해 워크플로에 연결하세요.</p>
      </Modal>

      {/* ---------- 테스트 실행: 입력 폼 / 실행 화면. 오른쪽 패널이라 캔버스(겹쳐 그린 상태)를 가리지 않는다 ---------- */}
      <Drawer open={drawer !== null} onClose={() => setDrawer(null)} placement="right" width={drawer === 'monitor' ? 620 : 480} mask={false} destroyOnHidden
              title={drawer === 'monitor' && testRunId ? `테스트 실행 · run #${testRunId}` : '테스트 실행 (초안 그래프)'}>
        {drawer === 'monitor' && testRunId ? <RunMonitor runId={testRunId} canvas={false} /> : (
          <div className={s.testForm}>
            <p className={s.muted} style={{ marginTop: 0 }}>저장한 초안 그래프로 실행합니다. 시작하면 진행 상황이 캔버스와 아래 실행 모니터에 그려져요.</p>
            <SchemaForm fields={withFileField(live.inputForm, fileAccepts, receiptReview)} submitting={startTest.isPending} submitLabel="테스트 실행"
                        onSubmit={(v) => startTest.mutate(v, {
                          onSuccess: (r) => { setTestRunId(r.runId); setDrawer(null); setExecTab('logs'); setView('editor'); },
                          onError: (e) => notify.error(e, '테스트 실행을 시작하지 못했습니다'),
                        })} />
          </div>
        )}
      </Drawer>
    </div>
  );
}

/** 자동화 빌더 캔버스(설계 6.6, 요구사항 4.6 P0): 워크플로 열기·노드 추가·연결·설정·검사·저장·테스트 실행 */
export function BuilderEditor({ id, onDirtyChange }: { id: number; onDirtyChange?: (dirty: boolean) => void }) {
  const wf = useBuilderWorkflow(id);
  const cat = useNodeDetails();
  // 처음 받은 초안으로 한 번만 편집 상태를 만든다(저장 후 다시 받아도 편집 중인 캔버스를 덮지 않는다)
  const [initial, setInitial] = useState<BuilderWorkflow | null>(null);
  useEffect(() => { if (wf.data && !initial) setInitial(wf.data); }, [wf.data, initial]);

  if (wf.isError) return <ErrorPanel error={wf.error} onRetry={() => wf.refetch()} />;
  if (cat.error) return <ErrorPanel error={cat.error} onRetry={cat.refetch} />;
  if (!initial || !cat.nodes || !cat.byType) return <Skeleton active={false} paragraph={{ rows: 10 }} />;
  return (
    <ReactFlowProvider>
      <Editor wf={initial} live={wf.data ?? initial} catalog={cat.nodes} byType={cat.byType} onDirtyChange={onDirtyChange} />
    </ReactFlowProvider>
  );
}
