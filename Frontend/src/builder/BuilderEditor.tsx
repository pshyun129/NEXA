import { useCallback, useEffect, useMemo, useRef, useState, type DragEvent } from 'react';
import { Button, Drawer, Input, InputNumber, Select, Skeleton, Tag } from 'antd';
import { Link } from 'react-router-dom';
import {
  Background, Controls, ReactFlow, ReactFlowProvider, addEdge, applyEdgeChanges, applyNodeChanges, useReactFlow,
  type Connection, type Edge, type EdgeChange, type Node, type NodeChange,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import {
  useBuilderWorkflow, useNodeCategories, useNodeDetails, useSaveGraph, useStartTestRun, useValidateGraph,
  type BuilderWorkflow, type Graph, type NodeDetail, type ValidationIssue,
} from '@/api/builder';
import { nodeTypes, type FlowNodeData, type NodeCategory } from '@/flow/FlowNode';
import { RunMonitor } from '@/automation/RunMonitor';
import { SchemaForm } from '@/automation/SchemaForm';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { Icon } from '@/ui/Icon';
import { CircleAlert, TriangleAlert } from '@/ui/icons';
import { Kbd, modKey } from '@/ui/primitives';
import { useNotify } from '@/ui/useNotify';
import { useHotkeys } from '@/common/useHotkeys';
import { formatTime, serverUtc } from '@/format';
import { orderWarnings } from './nodeIO';
import { DeployControls } from './DeployControls';
import { PlacementToggle } from './PlacementToggle';
import { InputFormEditor } from './InputFormEditor';
import s from './builder.module.css';

type BuilderNodeData = FlowNodeData & { nodeType: string; config: Record<string, unknown> };
type BNode = Node<BuilderNodeData, 'nexa'>;
type BEdge = Edge<{ branch: string | null }>;

const DND_TYPE = 'application/x-nexa-node';
const PALETTE_SEARCH_ID = 'nx-builder-node-search';
const EDGE_STYLE = { stroke: 'var(--nx-n-450)', strokeWidth: 1.5 };
const EDGE_ERROR_STYLE = { stroke: 'var(--nx-danger-fg)', strokeWidth: 2 };
/** 엣지 라벨 칩(style-guide 8.13): n-0, 1px n-200, radius 4, 12/500 n-700. 분기 이름에 색을 매핑하지 않는다 */
const EDGE_LABEL = {
  labelStyle: { fontSize: 12, fontWeight: 500, fill: 'var(--nx-n-700)' },
  labelBgStyle: { fill: 'var(--nx-n-0)', stroke: 'var(--nx-n-200)' },
  labelBgPadding: [6, 3] as [number, number],
  labelBgBorderRadius: 4,
};

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
    id: n.id, type: 'nexa', position: n.position,
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
  return {
    id: e.id, source: e.source, target: e.target, sourceHandle: e.branch ?? undefined,
    type: 'smoothstep', label: e.branch ?? undefined, data: { branch: e.branch }, style: EDGE_STYLE, ...EDGE_LABEL,
  };
}

function toGraph(nodes: BNode[], edges: BEdge[]): Graph {
  return {
    nodes: nodes.map((n) => ({
      id: n.id, type: n.data.nodeType,
      position: { x: Math.round(n.position.x), y: Math.round(n.position.y) },
      config: n.data.config,
    })),
    edges: edges.map((e) => ({ id: e.id, source: e.source, target: e.target, branch: e.data?.branch ?? null })),
  };
}

const nextId = (prefix: string, ids: string[]) =>
  `${prefix}${Math.max(0, ...ids.map((id) => Number(id.replace(/^\D+/, '')) || 0)) + 1}`;

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
              <Select size="small" allowClear value={empty ? undefined : String(v)} status={f.required && empty ? 'warning' : undefined}
                      options={f.options.map((o) => ({ value: o, label: o }))} onChange={(x) => onChange(f.key, x ?? '')} />
            ) : f.type === 'number' ? (
              <InputNumber size="small" style={{ width: '100%' }} value={empty ? null : Number(v)}
                           onChange={(x) => onChange(f.key, x ?? '')} />
            ) : (
              <Input size="small" value={empty ? '' : String(v)} status={f.required && empty ? 'warning' : undefined}
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

/**
 * wf는 처음 받은 초안(캔버스 초기값), live는 최신 서버 값(배포 버전·노출 화면 등 헤더 표시용).
 * 캔버스는 다시 받은 값으로 덮지 않고, 헤더는 배포·노출 변경이 바로 보이게 live를 쓴다.
 */
function Editor({ wf, live, catalog, byType }: { wf: BuilderWorkflow; live: BuilderWorkflow; catalog: NodeDetail[]; byType: Map<string, NodeDetail> }) {
  const notify = useNotify();
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
  const [savedAt, setSavedAt] = useState(wf.updatedAt);
  const [issues, setIssues] = useState<ValidationIssue[] | null>(null);
  const [q, setQ] = useState('');
  const [testOpen, setTestOpen] = useState(false);
  const [testRunId, setTestRunId] = useState<number | null>(null);

  const graph = useMemo(() => toGraph(nodes, edges), [nodes, edges]);
  const [formOpen, setFormOpen] = useState(false);
  // 파일 업로드 노드가 있는데 사원 입력 폼에 파일 칸이 없으면 사원이 파일을 올릴 수 없다(서버 검사는 이걸 보지 않는다)
  // 실행 첨부 파일(inputs.files)을 직접 읽는 노드: 파일 업로드, 그리고 'OCR할 파일'을 비운 OCR(FastAPI nodes/impl)
  const fileNode = graph.nodes.find((n) => n.type === 'file_upload'
    || (n.type === 'ocr_extract' && !String(n.config.files ?? '').trim()));
  const fileMissing = !!fileNode && !live.inputForm.some((f) => f.type === 'file');
  const warnings = useMemo(() => [
    ...orderWarnings(graph, catalog),
    ...(fileNode && fileMissing
      ? [{ nodeId: fileNode.id, message: `'${byType.get(fileNode.type)?.name ?? fileNode.type}'가 첨부 파일을 읽는데 사원 입력 폼에 파일 칸이 없습니다(사원이 파일을 올릴 수 없음)` }]
      : []),
  ], [graph, catalog, fileNode, fileMissing, byType]);
  const selectedNode = nodes.find((n) => n.selected);
  const selectedEdge = edges.find((e) => e.selected);

  // 바뀌면 서버 검사 결과는 지난 그래프 기준이 되므로 지운다(클라이언트 경고는 늘 현재 그래프 기준)
  const touch = () => { setDirty(true); setIssues(null); };

  // 검사 표시를 노드·엣지에 반영
  const errNodes = new Set(issues?.flatMap((i) => (i.nodeId ? [i.nodeId] : [])));
  const errEdges = new Set(issues?.flatMap((i) => (i.edgeId ? [i.edgeId] : [])));
  const warnNodes = new Set(warnings.map((w) => w.nodeId));
  const viewNodes = nodes.map((n) => {
    const issue = errNodes.has(n.id) ? 'error' as const : warnNodes.has(n.id) ? 'warning' as const : undefined;
    return n.data.issue === issue ? n : { ...n, data: { ...n.data, issue } };
  });
  const viewEdges = edges.map((e) => (errEdges.has(e.id) ? { ...e, style: EDGE_ERROR_STYLE } : e));

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
      return addEdge({ ...c, id, type: 'smoothstep', label: branch ?? undefined, data: { branch }, style: EDGE_STYLE, ...EDGE_LABEL }, es);
    });
    touch();
  }, []);

  const addNode = (type: string, position?: { x: number; y: number }) => {
    const d = byType.get(type);
    if (!d) return;
    const pos = position ?? flow.screenToFlowPosition({ x: window.innerWidth / 2, y: window.innerHeight / 2 });
    setNodes((ns) => [
      ...ns.map((n) => ({ ...n, selected: false })),
      { ...toNode({ id: nextId('n', ns.map((n) => n.id)), type, position: pos, config: {} }, byType), selected: true },
    ]);
    touch();
  };
  const onDrop = (e: DragEvent) => {
    e.preventDefault();
    const type = e.dataTransfer.getData(DND_TYPE);
    if (type) addNode(type, flow.screenToFlowPosition({ x: e.clientX - 120, y: e.clientY - 32 }));
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
    setEdges((es) => es.map((e) => (e.id === id ? { ...e, sourceHandle: branch, label: branch, data: { branch } } : e)));
    touch();
  };

  const doSave = async () => {
    const wf2 = await save.mutateAsync(graph);
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
      if (r.valid && warnings.length === 0) notify.success('검사를 통과했습니다');
    } catch (e) { notify.error(e, '검사하지 못했습니다'); }
  };
  const onTest = async () => {
    try {
      if (dirty) await doSave();
      setTestRunId(null);
      setTestOpen(true);
    } catch (e) { notify.error(e, '저장하지 못해 테스트 실행을 열 수 없습니다'); }
  };

  // '/' 노드 검색(설계 6.6 팔레트). 입력 중·모달 안에서는 useHotkeys가 무시한다
  useHotkeys({ '/': () => document.getElementById(PALETTE_SEARCH_ID)?.focus() });

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

  const focusNode = (id: string) => {
    setNodes((ns) => ns.map((n) => ({ ...n, selected: n.id === id })));
    setEdges((es) => es.map((e) => ({ ...e, selected: false })));
    flow.fitView({ nodes: [{ id }], duration: 300, maxZoom: 1.2 });
  };
  const focusEdge = (id: string) => {
    const e = edges.find((x) => x.id === id);
    if (!e) return;
    setEdges((es) => es.map((x) => ({ ...x, selected: x.id === id })));
    setNodes((ns) => ns.map((n) => ({ ...n, selected: false })));
    flow.fitView({ nodes: [{ id: e.source }, { id: e.target }], duration: 300, maxZoom: 1.2 });
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
  const selEdgeSource = selectedEdge ? nodes.find((n) => n.id === selectedEdge.source) : undefined;
  const selEdgeBranches = selEdgeSource?.data.branches ?? [];

  return (
    <div className={s.editor}>
      <header className={s.bar}>
        <Link to="/admin/builder" className={s.back}>← 자동화 빌더</Link>
        <h1 className={s.name}>{wf.name}</h1>
        {live.deployedVersion != null ? <Tag>v{live.deployedVersion} 배포됨</Tag> : <Tag>배포 전</Tag>}
        <span className={dirty ? s.unsaved : s.muted}>
          {dirty ? '저장 안 됨' : `초안 저장 ${formatTime(serverUtc(savedAt))}`}
        </span>
        <span className={s.spacer} />
        <Button onClick={onSave} loading={save.isPending} disabled={!dirty}>저장 <Kbd>{modKey}+S</Kbd></Button>
        <Button onClick={onValidate} loading={validate.isPending}>검사 <Kbd>{modKey}+Shift+V</Kbd></Button>
        <Button type="primary" onClick={onTest}>테스트 실행</Button>
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
      </header>

      <div className={s.body}>
        <aside className={s.palette} aria-label="노드 목록">
          <Input.Search id={PALETTE_SEARCH_ID} size="small" allowClear placeholder="노드 검색 /" aria-label="노드 검색" value={q}
                        onChange={(e) => setQ(e.target.value)} />
          {palette.map((g) => (
            <section key={g.code}>
              <h2 className={s.group}>{g.name}</h2>
              {g.items.map((n) => (
                <button key={n.nodeType} type="button" className={`${s.item} ${s[`cat-${n.category}`] ?? ''}`} draggable
                        title={`${n.description}\n(끌어다 놓거나 눌러서 추가)`}
                        onDragStart={(e) => { e.dataTransfer.setData(DND_TYPE, n.nodeType); e.dataTransfer.effectAllowed = 'move'; }}
                        onClick={() => addNode(n.nodeType)}>
                  {n.name}
                </button>
              ))}
            </section>
          ))}
        </aside>

        <div className={s.canvas} onDragOver={(e) => { e.preventDefault(); e.dataTransfer.dropEffect = 'move'; }} onDrop={onDrop}>
          <ReactFlow<BNode, BEdge>
            nodes={viewNodes} edges={viewEdges} nodeTypes={nodeTypes}
            onNodesChange={onNodesChange} onEdgesChange={onEdgesChange} onConnect={onConnect}
            deleteKeyCode={['Delete', 'Backspace']} fitView proOptions={{ hideAttribution: true }}
            defaultEdgeOptions={{ type: 'smoothstep', style: EDGE_STYLE }}
          >
            <Background gap={16} size={1} color="var(--nx-n-200)" />
            <Controls showInteractive={false} />
          </ReactFlow>
        </div>

        <aside className={s.panel} aria-label="설정">
          {selectedNode && selDetail ? (
            <>
              <h2 className={s.panelTitle}>설정: {selDetail.name}</h2>
              <p className={s.muted}>{selDetail.description} <span className="nx-mono">({selectedNode.id})</span></p>
              <ConfigForm detail={selDetail} config={selectedNode.data.config}
                          onChange={(k, v) => setConfig(selectedNode.id, k, v)} />
              {selDetail.outputs.length > 0 && (
                <div className={s.keys}>출력 키: {selDetail.outputs.map((o) => <code key={o.key} title={o.label}>{o.key}</code>)}</div>
              )}
              {selDetail.branches.length > 0 && <div className={s.keys}>분기: {selDetail.branches.join(' · ')}</div>}
              {warnings.filter((w) => w.nodeId === selectedNode.id).map((w) => (
                <p key={w.message} className={s.warnText}><Icon as={TriangleAlert} size={16} /> {w.message}</p>
              ))}
              <Button danger size="small" onClick={() => flow.deleteElements({ nodes: [{ id: selectedNode.id }] })}>노드 삭제</Button>
            </>
          ) : selectedEdge ? (
            <>
              <h2 className={s.panelTitle}>연결선</h2>
              <p className={s.muted}>{nodeName(selectedEdge.source)} → {nodeName(selectedEdge.target)}</p>
              {selEdgeBranches.length > 0 && (
                <label className={s.field}>
                  <span className={s.fieldLabel}>분기 결과<span className={s.req}> *</span></span>
                  <Select size="small" value={selectedEdge.data?.branch ?? undefined}
                          status={selectedEdge.data?.branch && selEdgeBranches.includes(selectedEdge.data.branch) ? undefined : 'error'}
                          options={selEdgeBranches.map((b) => ({ value: b, label: b }))}
                          onChange={(b) => setEdgeBranch(selectedEdge.id, b)} />
                </label>
              )}
              <Button danger size="small" onClick={() => flow.deleteElements({ edges: [{ id: selectedEdge.id }] })}>선 삭제</Button>
            </>
          ) : (
            <p className={s.muted}>노드나 연결선을 고르면 설정이 여기에 표시됩니다. 노드는 왼쪽 목록에서 끌어다 놓습니다. 선택 후 <Kbd>Delete</Kbd>로 지웁니다.</p>
          )}
        </aside>
      </div>

      <section className={s.issues} aria-label="검사 결과">
        <div className={s.issuesHead}>
          <span className={s.issuesTitle}>검사 결과 <span className="nx-num">{issueCount}</span></span>
          {issues === null && <span className={s.muted}>{dirty ? '변경 후 아직 서버 검사를 하지 않았습니다' : '[검사]로 서버 검사를 실행합니다'}</span>}
        </div>
        <ul className={s.issueList}>
        {issues?.map((i) => (
          <li key={`${i.nodeId}-${i.edgeId}-${i.message}`} className={s.issue}>
            <Icon as={CircleAlert} size={16} className={s.errIcon} />
            {i.nodeId ? `${nodeName(i.nodeId)}: ` : i.edgeId ? '연결선: ' : '그래프 전체: '}{i.message}
            {i.nodeId && <Button size="small" type="link" onClick={() => focusNode(i.nodeId!)}>노드로 이동</Button>}
            {!i.nodeId && i.edgeId && <Button size="small" type="link" onClick={() => focusEdge(i.edgeId!)}>선으로 이동</Button>}
          </li>
        ))}
        {warnings.map((w) => (
          <li key={`${w.nodeId}-${w.message}`} className={s.issue}>
            <Icon as={TriangleAlert} size={16} className={s.warnIcon} />{w.message}
            <Button size="small" type="link" onClick={() => focusNode(w.nodeId)}>노드로 이동</Button>
            {fileMissing && w.nodeId === fileNode?.id && w.message.includes('입력 폼') && (
              <Button size="small" type="link" onClick={() => setFormOpen(true)}>입력 폼 열기</Button>
            )}
          </li>
        ))}
        </ul>
      </section>

      <Drawer open={testOpen} onClose={() => setTestOpen(false)} placement="bottom" height="75%" destroyOnHidden
              title={testRunId ? `테스트 실행 · run #${testRunId} (초안 그래프)` : '테스트 실행 (초안 그래프)'}>
        {testRunId ? <RunMonitor runId={testRunId} /> : (
          <div className={s.testForm}>
            <SchemaForm fields={live.inputForm} submitting={startTest.isPending} submitLabel="테스트 실행"
                        onSubmit={(v) => startTest.mutate(v, {
                          onSuccess: (r) => setTestRunId(r.runId),
                          onError: (e) => notify.error(e, '테스트 실행을 시작하지 못했습니다'),
                        })} />
          </div>
        )}
      </Drawer>
    </div>
  );
}

/** 자동화 빌더 캔버스(설계 6.6, 요구사항 4.6 P0): 시드 워크플로 열기·노드 추가·연결·설정·검사·저장·테스트 실행 */
export function BuilderEditor({ id }: { id: number }) {
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
      <Editor wf={initial} live={wf.data ?? initial} catalog={cat.nodes} byType={cat.byType} />
    </ReactFlowProvider>
  );
}
