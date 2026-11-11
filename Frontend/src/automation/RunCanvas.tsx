import { useEffect, useMemo, useRef, useState } from 'react';
import { Background, BackgroundVariant, ReactFlow, ReactFlowProvider, useReactFlow, type Edge, type Node } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { isFinal, type RunStatus, type RunStep } from '@/api/ai';
import { useNodeDetails, type Graph } from '@/api/builder';
import { GRAPH_Y_SCALE, nodeTypes, type FlowNodeData, type NodeCategory, type NodeRunState } from '@/flow/FlowNode';
import { edgeTypes, type EdgeRunStatus, type FlowEdgeData } from '@/flow/FlowEdge';
import { formatElapsed, kst, serverUtc } from '@/format';
import s from './hub.module.css';

type RNode = Node<FlowNodeData, 'nexa'>;
type REdge = Edge<FlowEdgeData, 'flow'>;

/**
 * 실행 화면의 큰 캔버스: 빌더와 같은 노드 카드·움직임으로 지금 어느 단계인지 보여 준다(읽기 전용).
 * 지금 단계가 바뀌면 그 노드로 화면을 부드럽게 옮긴다. 노드 상태·선 색칠 규칙은 빌더 테스트 실행 표시와 같다.
 */
function Canvas({ graph, steps, status }: { graph: Graph; steps: RunStep[]; status: RunStatus | undefined }) {
  const details = useNodeDetails();
  const flow = useReactFlow<RNode, REdge>();
  const final = isFinal(status);

  const byNode = useMemo(() => {
    const m = new Map<string, RunStep>();
    for (const st of steps) m.set(st.nodeId, st);          // 같은 노드를 다시 지나면 마지막 기록
    return m;
  }, [steps]);

  const nodes: RNode[] = useMemo(() => graph.nodes.map((n) => {
    const d = details.byType?.get(n.type);
    const st = byNode.get(n.id);
    const state: NodeRunState = st ? st.status : final ? 'SKIPPED' : 'idle';
    const dur = st?.finishedAt ? kst(serverUtc(st.finishedAt)).diff(kst(serverUtc(st.startedAt))) : null;
    return {
      id: n.id, type: 'nexa', position: { x: n.position.x, y: n.position.y * GRAPH_Y_SCALE },
      draggable: false, selectable: false, connectable: false,
      data: {
        title: d?.name ?? n.type, nodeType: n.type, category: (d?.category ?? 'input') as NodeCategory,
        subtitle: st?.branch ? `→ ${st.branch}` : d?.description.split(/[(.]/)[0].trim(),
        branches: d?.branches.length ? d.branches : undefined,
        state, elapsed: dur != null ? formatElapsed(dur) : undefined,
      },
    };
  }), [graph, details.byType, byNode, final]);

  const edges: REdge[] = useMemo(() => graph.edges.map((e) => {
    const a = byNode.get(e.source);
    const b = byNode.get(e.target);
    let st: EdgeRunStatus = 'idle';
    // 분기 노드는 실제로 나간 분기의 선만 칠한다
    const taken = a?.status === 'SUCCEEDED' && (e.branch == null || a.branch == null || e.branch === a.branch);
    if (taken && b) st = b.status === 'RUNNING' || b.status === 'WAITING' ? 'running' : 'success';
    return { id: e.id, source: e.source, target: e.target, sourceHandle: e.branch ?? undefined, type: 'flow', data: { branch: e.branch, status: st } };
  }), [graph, byNode]);

  // 처음엔 전체를 맞춰 보여 주고(fitView), 실행 중·확인 대기 단계가 있으면 그 노드로 크게 따라간다(끝난 실행은 전체 그대로).
  // 확대·이동 장치가 준비된 뒤(onInit)에, 노드 크기를 잴 시간을 조금 두고 움직여야 무시되지 않는다.
  // (읽기 전용이라 onNodesChange가 없어 useNodesInitialized는 쓸 수 없다: 잰 크기가 props로 돌아오지 않음)
  const current = [...steps].reverse().find((x) => x.status === 'RUNNING' || x.status === 'WAITING') ?? null;
  const [inited, setInited] = useState(false);
  const fitted = useRef(false);
  useEffect(() => {
    if (!inited) return;
    const t = window.setTimeout(() => {
      const follow = () => {
        const n = current && flow.getNode(current.nodeId);
        if (n) void flow.setCenter(n.position.x + 107, n.position.y + 100, { zoom: 0.85, duration: 800 });
      };
      // fitView는 비동기로 끝나므로 끝난 뒤에 따라가야 덮이지 않는다
      if (!fitted.current) { fitted.current = true; void flow.fitView({ padding: 0.16, maxZoom: 1 }).then(follow); } else follow();
    }, fitted.current ? 60 : 300);
    return () => window.clearTimeout(t);
  }, [inited, current?.nodeId, current?.status, flow]);

  return (
    <ReactFlow<RNode, REdge>
      nodes={nodes} edges={edges} nodeTypes={nodeTypes} edgeTypes={edgeTypes}
      nodesDraggable={false} nodesConnectable={false} elementsSelectable={false} edgesFocusable={false}
      minZoom={0.2} maxZoom={1.4} onInit={() => setInited(true)}
      proOptions={{ hideAttribution: true }}
    >
      <Background variant={BackgroundVariant.Dots} gap={20} size={1} color="#d9dce2" />
    </ReactFlow>
  );
}

export function RunCanvas({ graph, steps, status, note }:
  { graph: Graph | undefined; steps: RunStep[]; status: RunStatus | undefined; note?: string }) {
  if (!graph || graph.nodes.length === 0) return null;
  return (
    <div className={s.runCanvas}>
      <ReactFlowProvider><Canvas graph={graph} steps={steps} status={status} /></ReactFlowProvider>
      {note && <span className={s.runCanvasNote}>{note}</span>}
    </div>
  );
}
