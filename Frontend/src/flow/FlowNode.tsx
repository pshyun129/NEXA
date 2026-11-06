import { Handle, Position, type Node, type NodeProps } from '@xyflow/react';
import { Icon } from '@/ui/Icon';
import { Check, CircleAlert, Clock, Sparkles, TriangleAlert } from '@/ui/icons';
import { StatusBadge } from '@/ui/StatusBadge';
import s from './flow.module.css';

/** 서버 노드 카테고리 code(FastAPI registry.py) 5종 */
export type NodeCategory = 'input' | 'ai' | 'rule' | 'human' | 'output';
export type NodeRunState = 'idle' | 'QUEUED' | 'RUNNING' | 'WAITING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED';

export type FlowNodeData = {
  title: string;        // 카탈로그 name
  subtitle?: string;
  category: NodeCategory;
  state?: NodeRunState;
  elapsed?: string;     // "2.1s"
  selected?: boolean;
  /** 빌더: 분기 이름(카탈로그 branches). 있으면 분기마다 출구 핸들(id = 분기 이름)을 둔다 */
  branches?: string[];
  /** 빌더 검사 표시: error = 서버 검사 이슈, warning = 클라이언트 순서 경고(style-guide 8.13) */
  issue?: 'error' | 'warning';
};
export type FlowNodeType = Node<FlowNodeData, 'nexa'>;

/**
 * 빌더와 실행 모니터가 같이 쓰는 커스텀 노드 1종(style-guide 8.13). 1주차 시제품.
 * 상태는 테두리로만 표시한다(오버레이·스피너 금지, opacity 금지).
 */
export function FlowNode({ data, selected }: NodeProps<FlowNodeType>) {
  const state = data.state ?? 'idle';
  const branches = data.branches ?? [];
  const cls = [s.node, s[`cat-${data.category}`], s[`st-${state}`], state === 'RUNNING' ? 'nx-running' : '',
    data.issue ? s[`issue-${data.issue}`] : '', selected ? s.selected : '', branches.length ? s.hasBranches : ''].join(' ');
  return (
    <div className={cls}>
      <Handle type="target" position={Position.Left} className={s.handle} />
      <div className={s.title}>
        {data.category === 'ai' && <Icon as={Sparkles} size={12} className={s.aiIcon} />}
        <span>{data.title}</span>
      </div>
      <div className={s.sub}>
        {data.category === 'ai' ? `AI · ${data.subtitle ?? ''}` : data.subtitle}
        {state === 'SKIPPED' && <span className={s.skipped}> · 실행 안 됨</span>}
      </div>
      <div className={s.corner}>
        {data.issue === 'error' && <Icon as={CircleAlert} size={12} className={s.errorIcon} label="검사 오류" />}
        {data.issue === 'warning' && <Icon as={TriangleAlert} size={12} className={s.warnIcon} label="순서 경고" />}
        {state === 'QUEUED' && <Icon as={Clock} size={12} className={s.queuedIcon} />}
        {state === 'SUCCEEDED' && <Icon as={Check} size={12} className={s.okIcon} />}
        {state === 'WAITING' && <StatusBadge domain="RunStatus" value="WAITING" />}
        {data.elapsed && (state === 'RUNNING' || state === 'SUCCEEDED') && (
          <span className={s.chip}>{state === 'RUNNING' ? `경과 ${data.elapsed}` : data.elapsed}</span>
        )}
      </div>
      {branches.length === 0
        ? <Handle type="source" position={Position.Right} className={s.handle} />
        : branches.map((b, i) => {
          // 분기 출구는 오른쪽 변에 고르게 나눠 두고, 노드 안쪽에 분기 이름을 붙인다(바깥에 두면 연결선·다음 노드와 겹친다)
          const top = `${((i + 1) / (branches.length + 1)) * 100}%`;
          return (
            <Handle key={b} id={b} type="source" position={Position.Right} className={s.handle} style={{ top }}>
              <span className={s.branchLabel}>{b}</span>
            </Handle>
          );
        })}
    </div>
  );
}

export const nodeTypes = { nexa: FlowNode };
