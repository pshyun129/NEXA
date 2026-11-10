import { BaseEdge, EdgeLabelRenderer, getBezierPath, type Edge, type EdgeProps } from '@xyflow/react';
import s from './flow.module.css';

/** idle = 회색, running = 파랑 점선 + 흐르는 점, success = 초록, error = 빨강(서버 검사 이슈) */
export type EdgeRunStatus = 'idle' | 'running' | 'success' | 'error';
export type FlowEdgeData = { branch: string | null; status?: EdgeRunStatus };
export type FlowEdgeType = Edge<FlowEdgeData, 'flow'>;

const STROKE: Record<EdgeRunStatus, string> = { idle: '#d4d8df', running: '#7d93d0', success: '#67ac97', error: 'var(--nx-danger-fg)' };

/** MONO Workflow Studio FlowEdge: 곡률 .38 베지어. 분기 이름은 선 가운데 칩으로 */
export function FlowEdge(props: EdgeProps<FlowEdgeType>) {
  const [path, lx, ly] = getBezierPath({ ...props, curvature: 0.38 });
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
