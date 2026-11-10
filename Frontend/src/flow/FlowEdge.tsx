import { BaseEdge, EdgeLabelRenderer, getBezierPath, useEdges, type Edge, type EdgeProps } from '@xyflow/react';
import s from './flow.module.css';

/** idle = 회색, running = 파랑 점선 + 흐르는 점, success = 초록, error = 빨강(서버 검사 이슈) */
export type EdgeRunStatus = 'idle' | 'running' | 'success' | 'error';
export type FlowEdgeData = { branch: string | null; status?: EdgeRunStatus };
export type FlowEdgeType = Edge<FlowEdgeData, 'flow'>;

const STROKE: Record<EdgeRunStatus, string> = { idle: '#d4d8df', running: '#7d93d0', success: '#67ac97', error: 'var(--nx-danger-fg)' };

const CURVATURE = 0.38;
/** React Flow getBezierPath와 같은 제어점 거리 */
const ctrl = (d: number) => (d >= 0 ? 0.5 * d : CURVATURE * 25 * Math.sqrt(-d));
/** 오른쪽 출구 → 왼쪽 입구 베지어 위 t 지점 */
function pointAt(t: number, sx: number, sy: number, tx: number, ty: number) {
  const off = ctrl(tx - sx);
  const [x1, y1, x2, y2] = [sx + off, sy, tx - off, ty];
  const u = 1 - t;
  return [u ** 3 * sx + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t ** 3 * tx, u ** 3 * sy + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t ** 3 * ty];
}

/**
 * MONO Workflow Studio FlowEdge: 곡률 .38 베지어. 분기 이름은 선 위 칩으로.
 * 같은 노드에서 나간 분기 선이 여럿이면 칩을 선 위 서로 다른 지점(앞쪽~뒤쪽)에 놓아 겹치지 않게 한다(QA AUTO-C2)
 */
export function FlowEdge(props: EdgeProps<FlowEdgeType>) {
  const [path, mx, my] = getBezierPath({ ...props, curvature: CURVATURE });
  const siblings = useEdges<FlowEdgeType>().filter((e) => e.source === props.source && e.data?.branch).map((e) => e.id).sort();
  const idx = siblings.indexOf(props.id);
  const [lx, ly] = siblings.length > 1 && idx >= 0
    ? pointAt(0.3 + (0.4 * idx) / (siblings.length - 1), props.sourceX, props.sourceY, props.targetX, props.targetY)
    : [mx, my];
  const status = props.data?.status ?? 'idle';
  const branch = props.data?.branch;
  return (
    <>
      <BaseEdge id={props.id} path={path} interactionWidth={26}
                style={{ stroke: props.selected ? '#3a3b3e' : STROKE[status], strokeWidth: status === 'idle' && !props.selected ? 1.5 : 2, transition: 'stroke .35s' }} />
      {status === 'running' && (
        <g className={s.edgeRun}>
          <path d={path} className={s.edgeTrail} />
          <circle r="4" fill="#7d93d0"><animateMotion dur="1.35s" repeatCount="indefinite" path={path} /></circle>
          <circle r="8" fill="#7d93d0" opacity=".15"><animateMotion dur="1.35s" repeatCount="indefinite" path={path} /></circle>
        </g>
      )}
      {branch && (
        <EdgeLabelRenderer>
          <span className={`${s.edgeLabel} ${status === 'running' || status === 'success' ? s.edgeLabelOn : ''}`}
                style={{ transform: `translate(-50%, -50%) translate(${lx}px, ${ly}px)` }}>{branch}</span>
        </EdgeLabelRenderer>
      )}
    </>
  );
}

export const edgeTypes = { flow: FlowEdge };
