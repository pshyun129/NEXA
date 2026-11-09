import type { DocumentDetail, ApprovalLineItem } from '@/api/approval';
import { EmpName } from '@/ui/EmpName';
import { Icon } from '@/ui/Icon';
import { Check, X, SkipForward, Lock, Circle } from '@/ui/icons';
import { formatDateTime } from '@/format';
import { lineRoleLabel } from '@/status/labels';
import { currentLine, orderedApprovers } from './permissions';
import s from './approval.module.css';

type NodeState = 'done' | 'current' | 'pending' | 'rejected' | 'skipped';

function stateOf(l: ApprovalLineItem, current: ApprovalLineItem | null): NodeState {
  if (current && l.lineId === current.lineId) return 'current';
  switch (l.action) {
    case 'AGREE': return 'done';
    case 'DISAGREE': return 'rejected';
    case 'SKIPPED': return 'skipped';
    default: return 'pending';
  }
}

const STATE_TEXT: Record<NodeState, string> = {
  done: '승인', current: '현재 차례', pending: '대기', rejected: '반려', skipped: '전결 생략',
};

function Node({ state, role, empId, at, required, meId }:
  { state: NodeState; role: string; empId: number; at: string | null; required?: boolean; meId: number | null }) {
  const icon = state === 'done' ? Check : state === 'rejected' ? X : state === 'skipped' ? SkipForward : state === 'pending' ? Circle : null;
  return (
    <li className={[s.node, s[`n-${state}`]].join(' ')} aria-label={`${role} ${STATE_TEXT[state]}`}>
      <span className={s.dotWrap}>
        <span className={s.nodeDot}>{icon && <Icon as={icon} size={12} />}</span>
      </span>
      <span className={s.nodeRole}>
        {role}
        {required && <span className={s.req}><Icon as={Lock} size={12} />필수</span>}
      </span>
      <span className={s.nodeName}>
        <EmpName id={empId} />{empId === meId && <span className={s.me}>(나)</span>}
      </span>
      <span className={s.nodeAt}>{state === 'current' ? '현재' : at ? formatDateTime(at, true) : STATE_TEXT[state]}</span>
    </li>
  );
}

/** 결재선 가로 스테퍼(설계 6.2). 참조·열람은 점선 뒤에 ◇로 분리한다. */
export function ApprovalLine({ doc, meId }: { doc: DocumentDetail; meId: number | null }) {
  const current = currentLine(doc);
  const approvers = orderedApprovers(doc);
  const others = doc.approvalLines.filter((l) => l.role === 'REFERENCE' || l.role === 'VIEWER');
  return (
    <div className={s.line}>
      <ol className={s.steps}>
        <Node state="done" role="기안" empId={doc.drafterId} at={doc.draftedAt} meId={meId} />
        {approvers.map((l) => (
          <Node key={l.lineId} state={stateOf(l, current)} role={lineRoleLabel(l.role)} empId={l.empId}
                at={l.actedAt} required={l.required} meId={meId} />
        ))}
      </ol>
      {others.length > 0 && (
        <ul className={s.others}>
          {others.map((l) => (
            <li key={l.lineId}>◇ {lineRoleLabel(l.role)} <EmpName id={l.empId} /></li>
          ))}
        </ul>
      )}
    </div>
  );
}
