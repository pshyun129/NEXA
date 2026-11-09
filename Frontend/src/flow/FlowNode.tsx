import { Handle, Position, type Node, type NodeProps } from '@xyflow/react';
import type { LucideIcon } from 'lucide-react';
import { Icon } from '@/ui/Icon';
import { CircleAlert, GitBranch, MoreHorizontal, Square, TriangleAlert, User, Zap, Check } from '@/ui/icons';
import { Illustration, type ArtKind } from './Illustration';
import s from './flow.module.css';

/** 서버 노드 카테고리 code(FastAPI registry.py) 5종 */
export type NodeCategory = 'input' | 'ai' | 'rule' | 'human' | 'output';
export type NodeRunState = 'idle' | 'QUEUED' | 'RUNNING' | 'WAITING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED';

export type FlowNodeData = {
  title: string;        // 카탈로그 name
  subtitle?: string;
  category: NodeCategory;
  /** 노드 종류(카탈로그 nodeType). 그림·색·라벨을 고르는 데 쓴다 */
  nodeType?: string;
  state?: NodeRunState;
  elapsed?: string;     // "2.1s"
  selected?: boolean;
  /** 빌더: 분기 이름(카탈로그 branches). 있으면 분기마다 출구 핸들(id = 분기 이름)을 둔다 */
  branches?: string[];
  /** 빌더 검사 표시: error = 서버 검사 이슈, warning = 클라이언트 순서 경고(style-guide 8.13) */
  issue?: 'error' | 'warning';
};
export type FlowNodeType = Node<FlowNodeData, 'nexa'>;

/** 파스텔 색 6종(MONO Workflow Studio) */
export type Tone = 'gold' | 'blue' | 'lavender' | 'coral' | 'mint' | 'slate';
export const TONE_COLOR: Record<Tone, string> = {
  gold: '#d49a3d', blue: '#6688d9', lavender: '#a280d0', coral: '#db8d74', mint: '#569c97', slate: '#7f91a9',
};

interface Look { art: ArtKind; tone: Tone; app: string; tag?: string }
/** 노드 종류별 그림·색·하단 라벨. 모르는 종류는 카테고리 기본값 */
const LOOK: Record<string, Look> = {
  file_upload: { art: 'trig', tone: 'gold', app: '첨부 파일', tag: 'TRIGGER' },
  employee_lookup: { art: 'person', tone: 'blue', app: '인사 정보' },
  attendance_lookup: { art: 'clock', tone: 'blue', app: '근태 기록' },
  leave_balance: { art: 'leave', tone: 'gold', app: '휴가 잔액' },
  ocr_extract: { art: 'ocr', tone: 'blue', app: 'OCR 엔진' },
  doc_classify: { art: 'cls', tone: 'mint', app: 'AI 분류' },
  structured_extract: { art: 'cls', tone: 'mint', app: 'AI 추출' },
  llm_verify: { art: 'ext', tone: 'lavender', app: '교차 검증', tag: 'ROUTER' },
  policy_check: { art: 'pol', tone: 'blue', app: '경비 규정' },
  free_prompt: { art: 'op', tone: 'lavender', app: 'AI 작성' },
  calculate: { art: 'report', tone: 'mint', app: '계산' },
  human_confirm: { art: 'hum', tone: 'coral', app: '담당자' },
  approval_draft: { art: 'appr', tone: 'gold', app: '전자결재' },
  wait_approval: { art: 'appr', tone: 'coral', app: '결재 결과' },
  excel_write: { art: 'xls', tone: 'mint', app: '경비 대장' },
  send_mail: { art: 'mail', tone: 'coral', app: '메일' },
  complete: { art: 'done', tone: 'slate', app: '알림', tag: 'COMPLETE' },
};
const CAT_LOOK: Record<NodeCategory, Look> = {
  input: { art: 'person', tone: 'blue', app: '입력' },
  ai: { art: 'ocr', tone: 'lavender', app: 'AI' },
  rule: { art: 'ext', tone: 'lavender', app: '규칙', tag: 'ROUTER' },
  human: { art: 'hum', tone: 'coral', app: '사람' },
  output: { art: 'done', tone: 'gold', app: '출력', tag: 'ACTION' },
};
const CAT_TAG: Record<NodeCategory, string> = { input: 'INPUT', ai: 'AI', rule: 'ROUTER', human: 'HUMAN', output: 'ACTION' };

export function lookOf(nodeType: string | undefined, category: NodeCategory): Look {
  return (nodeType && LOOK[nodeType]) || CAT_LOOK[category] || CAT_LOOK.input;
}

const tagIcon = (tag: string): LucideIcon =>
  tag === 'TRIGGER' ? Zap : tag === 'ROUTER' ? GitBranch : tag === 'HUMAN' ? User : tag === 'COMPLETE' ? Check : Square;

const STATE_LABEL: Record<NodeRunState, string> = {
  idle: '실행 대기', QUEUED: '대기열', RUNNING: '실행 중', WAITING: '확인 대기', SUCCEEDED: '완료', FAILED: '실행 실패', SKIPPED: '실행 안 됨',
};

/**
 * 빌더 캔버스 노드(MONO Workflow Studio WorkNode, 214 × 200).
 * 카테고리마다 파스텔 색과 그림, 실행 상태는 테두리·하단 상태 줄로 보이고, 실행 중에는 그림이 움직인다.
 */
export function FlowNode({ data, selected }: NodeProps<FlowNodeType>) {
  const state = data.state ?? 'idle';
  const branches = data.branches ?? [];
  const look = lookOf(data.nodeType, data.category);
  const tag = look.tag ?? (branches.length ? 'ROUTER' : CAT_TAG[data.category] ?? 'NODE');
  const cls = [s.node, s[`tone-${look.tone}`], s[`st-${state}`],
    data.issue ? s[`issue-${data.issue}`] : '', selected ? s.selected : ''].join(' ');
  return (
    <div className={cls}>
      <Handle type="target" position={Position.Left} className={s.handle} />
      <div className={s.top}>
        <span className={s.cat}><Icon as={tagIcon(tag)} size={12} /> {tag}</span>
        <span className={s.corner}>
          {data.issue === 'error' ? <Icon as={CircleAlert} size={16} className={s.errorIcon} label="검사 오류" />
            : data.issue === 'warning' ? <Icon as={TriangleAlert} size={16} className={s.warnIcon} label="순서 경고" />
            : <Icon as={MoreHorizontal} size={16} />}
        </span>
      </div>
      <div className={s.art}>
        <Illustration kind={look.art} className={s.ill} />
        <span className={s.orbit} /><span className={s.speck} />
      </div>
      <div className={s.copy}>
        <strong>{data.title}</strong>
        <p>{data.subtitle || ' '}</p>
      </div>
      <div className={s.bottom}>
        <span className={s.appDot} />
        <span className={s.app}>{look.app}</span>
        <span className={s.state}>
          {state === 'RUNNING' ? <i className={s.ring} /> : <i className={s.dot} />}
          {STATE_LABEL[state]}{data.elapsed && state === 'SUCCEEDED' && <span className="nx-num"> · {data.elapsed}</span>}
        </span>
      </div>
      {state === 'RUNNING' && <span className={s.runLine} />}
      {branches.length === 0
        ? <Handle type="source" position={Position.Right} className={s.handle} />
        : branches.map((b, i) => {
          // 분기 출구는 그림·제목 높이(60~150px)에 고르게 두고, 이름은 노드 바깥 오른쪽 위에 작은 칩으로 붙인다
          const top = 60 + ((i + 1) / (branches.length + 1)) * 90;
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
