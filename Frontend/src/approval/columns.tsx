import { Tooltip } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { DocumentSummary } from '@/api/approval';
import { StatusBadge } from '@/ui/StatusBadge';
import { EmpName } from '@/ui/EmpName';
import { Icon } from '@/ui/Icon';
import { Sparkles } from '@/ui/icons';
import { formatDateShort } from '@/format';
import { formTypeLabel } from '@/status/labels';
import { shortNo } from './DocumentView';
import s from './approval.module.css';

/** 진행 칸: 진행중이면 단계 n/m + (내 차례 배지 | 현재 결재자), 끝났으면 — */
function Progress({ r }: { r: DocumentSummary }) {
  if (r.status !== 'INPROGRESS' || r.currentStep == null) return <span className={s.cellSub}>—</span>;
  return (
    <div className={s.progress}>
      <span className="nx-num">{r.currentStep}/{r.totalSteps}</span>
      {r.myTurn
        ? <StatusBadge domain="MyTurn" value="MY_TURN" />
        : r.currentApproverIds.length > 0 && (
          <span className={s.cellSub}>
            {r.currentApproverIds.map((id, i) => <span key={id}>{i > 0 && ', '}<EmpName id={id} /></span>)}
          </span>
        )}
    </div>
  );
}

/** 문서 목록 칸(결재함·홈 결재 대기 공용). 번호는 docNo 끝 4자리, ✦자동화는 출처가 AUTOMATION일 때(설계 6.1), 툴팁은 sourceLabel(워크플로 이름). */
export function docColumns(opt: { drafter?: boolean; status?: boolean; progress?: boolean }): ColumnsType<DocumentSummary> {
  return [
    {
      title: '문서', key: 'doc',
      render: (_, r) => (
        <div style={{ minWidth: 0 }}>
          <span className={s.cellTitle}>{r.title}</span>
          <span className={s.cellSub}>
            <span className="nx-num">{shortNo(r.docNo)}</span> · {formTypeLabel(r.formType)}
            {r.sourceType === 'AUTOMATION' && (
              // 워크플로 이름(sourceLabel)은 좁은 목록 칸 대신 툴팁으로. 없으면 표식만
              <Tooltip title={r.sourceLabel ?? undefined}>
                <span className={s.autoTag} aria-label={r.sourceLabel ? `자동화: ${r.sourceLabel}` : undefined}>
                  <Icon as={Sparkles} size={12} />자동화
                </span>
              </Tooltip>
            )}
          </span>
        </div>
      ),
    },
    ...(opt.drafter ? [{ title: '기안자', dataIndex: 'drafterId', width: 90, render: (v: number) => <EmpName id={v} /> }] : []),
    { title: '기안일', dataIndex: 'draftedAt', width: 96, render: (v: string) => <span className="nx-num">{formatDateShort(v)}</span> },
    ...(opt.status ? [{ title: '상태', dataIndex: 'status', width: 90, render: (v: string) => <StatusBadge domain="DocumentStatus" value={v} /> }] : []),
    ...(opt.progress ? [{ title: '진행', key: 'progress', width: 130, render: (_: unknown, r: DocumentSummary) => <Progress r={r} /> }] : []),
  ];
}
