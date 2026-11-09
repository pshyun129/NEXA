import { useRef, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Button, Input, Skeleton } from 'antd';
import { useDocument, useDocumentAction, type DocumentAction, type DocumentDetail, type ApprovalHistoryItem } from '@/api/approval';
import { useMyId } from '@/auth/session';
import { StatusBadge } from '@/ui/StatusBadge';
import { EmpName } from '@/ui/EmpName';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { ConfirmAction } from '@/ui/ConfirmAction';
import { AiMark, Kbd, modKey } from '@/ui/primitives';
import { useNotify } from '@/ui/useNotify';
import { formatDateTime, formatDuration, formatMoney } from '@/format';
import { approvalLevelLabel, formTypeLabel } from '@/status/labels';
import { useHotkeys } from '@/common/useHotkeys';
import { requestHref } from '@/attendance/requests/links';
import { APPLY_PATH, leaveRequestHref } from '@/leave/links';
import { ApprovalLine } from './ApprovalLine';
import { ReferrerEditor, ResubmitButton } from './DraftTools';
import { documentPermissions, type DocumentPermissions } from './permissions';
import s from './approval.module.css';

/** 표 번호: docNo 끝 4자리(#0409). 전체 docNo는 상세 헤더에 mono로 둔다(설계 6.1). */
export const shortNo = (docNo: string) => `#${docNo.slice(-4)}`;

const SOURCE_TEXT: Record<string, string> = { LEAVE: '휴가 신청', ATTENDANCE: '근태 신청' };

function Attributes({ doc }: { doc: DocumentDetail }) {
  const a = doc.attributes;
  const items: [string, ReactNode][] = [];
  if (a?.amount != null) items.push(['금액', formatMoney(a.amount)]);
  if (a?.overseas != null) items.push(['출장', a.overseas ? '해외' : '국내']);
  if (a?.days != null) items.push(['휴가 일수', `${a.days}일`]);
  if (a?.overtimeMinutes != null) items.push(['신청 시간', formatDuration(a.overtimeMinutes)]);
  if (a?.monthlyOvertimeMinutes != null) items.push(['이달 누적 초과근무', formatDuration(a.monthlyOvertimeMinutes)]);
  if (doc.approvalLevel) items.push(['전결 단계', approvalLevelLabel(doc.approvalLevel)]);
  if (items.length === 0) return null;
  return (
    <dl className={s.attrs}>
      {items.map(([k, v]) => (
        <div key={k}><dt>{k}</dt><dd className="nx-num">{v}</dd></div>
      ))}
    </dl>
  );
}

/** 이력: 재기안 차수별로 묶는다(요구사항 3.4). */
function Histories({ list }: { list: ApprovalHistoryItem[] }) {
  const rounds = [...new Set(list.map((h) => h.round))].sort((a, b) => a - b);
  return (
    <div className={s.hist}>
      {rounds.map((r) => (
        <div key={r}>
          {rounds.length > 1 && <div className={s.histRound}>{r}차</div>}
          <ol className={s.histList}>
            {list.filter((h) => h.round === r).map((h) => (
              <li key={h.historyId}>
                <span className={`${s.histAt} nx-num`}>{formatDateTime(h.occurredAt, true)}</span>
                <StatusBadge domain="HistoryAction" value={h.action} />
                <EmpName id={h.actorId} />
                {h.targetEmpId != null && <span className={s.muted}>→ <EmpName id={h.targetEmpId} /></span>}
                {h.comment && <span className={s.histComment}>“{h.comment}”</span>}
              </li>
            ))}
          </ol>
        </div>
      ))}
    </div>
  );
}

function ActionBar({ doc, p, onProcessed }: { doc: DocumentDetail; p: DocumentPermissions; onProcessed?: (id: number) => void }) {
  const action = useDocumentAction(doc.documentId);
  const notify = useNotify();
  const [rejecting, setRejecting] = useState(false);
  const [comment, setComment] = useState('');
  const openFinal = useRef<(() => void) | null>(null);

  const run = (a: DocumentAction, done: string) =>
    action.mutateAsync(a).then(
      () => {
        const fromSource = doc.sourceType === 'LEAVE' || doc.sourceType === 'ATTENDANCE';
        notify.success(`${shortNo(doc.docNo)} ${done}`, fromSource ? '원본 반영은 몇 초 걸릴 수 있습니다.' : undefined);
        setRejecting(false);
        setComment('');
        // 승인·반려·전결 뒤에는 다음 문서로 넘어간다(설계 6.2 연속 처리). 회수는 기안자 동작이라 제자리
        if (a.kind !== 'cancel') onProcessed?.(doc.documentId);
      },
      (e) => notify.error(e),
    );

  const submitReject = () => {
    const c = comment.trim();
    if (c.length === 0 || c.length > 1000) return;
    run({ kind: 'reject', comment: c }, '반려 완료');
  };

  // 결재 단축키(설계 7.2): A 승인, R 반려, F 전결. 처리 중이거나 반려 입력 중이면 끈다
  const approve = () => run({ kind: 'approve' }, '승인 완료');
  useHotkeys({
    a: approve,
    r: () => setRejecting(true),
    ...(p.canFinalApprove ? { f: () => openFinal.current?.() } : {}),
  }, p.myTurn && !action.isPending && !rejecting);

  if (p.myTurn) {
    return (
      <div className={s.actionWrap}>
        {rejecting && (
          <div className={s.reject}>
            <Input.TextArea
              autoFocus
              value={comment}
              onChange={(e) => setComment(e.target.value)}
              maxLength={1000}
              showCount
              autoSize={{ minRows: 2, maxRows: 6 }}
              placeholder="반려 사유를 입력해 주세요(필수)"
              aria-label="반려 사유"
              onKeyDown={(e) => {
                if (e.key === 'Escape') { setRejecting(false); }
                if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); submitReject(); }
              }}
            />
            <div className={s.rejectFoot}>
              <span className={s.muted}><Kbd>{modKey}</Kbd>+<Kbd>Enter</Kbd> 제출 · <Kbd>Esc</Kbd> 닫기</span>
              <Button onClick={() => setRejecting(false)} disabled={action.isPending}>취소</Button>
              <Button danger type="primary" loading={action.isPending} disabled={comment.trim().length === 0} onClick={submitReject}>
                반려 제출
              </Button>
            </div>
          </div>
        )}
        <div className={s.actionBar}>
          {!rejecting && <Button danger onClick={() => setRejecting(true)} disabled={action.isPending}>반려 <Kbd>R</Kbd></Button>}
          {p.canFinalApprove && (
            <ConfirmAction
              title="전결로 승인합니다"
              confirmLabel="전결"
              onConfirm={() => run({ kind: 'final-approve' }, '전결 완료')}
              trigger={(open) => { openFinal.current = open; return <Button onClick={open} disabled={action.isPending}>전결 <Kbd>F</Kbd></Button>; }}
            >
              {p.skipped.length > 0 ? (
                <>
                  이후 결재자 {p.skipped.length}명(
                  {p.skipped.map((l, i) => <span key={l.lineId}>{i > 0 && ', '}<EmpName id={l.empId} /></span>)}
                  )의 결재가 생략됩니다.
                </>
              ) : '남은 결재자가 없어 승인과 같습니다.'}
            </ConfirmAction>
          )}
          <span className={s.spacer} />
          <Button type="primary" loading={action.isPending && action.variables?.kind === 'approve'}
                  disabled={action.isPending} onClick={approve}>
            승인 <Kbd onPrimary>A</Kbd>
          </Button>
        </div>
      </div>
    );
  }

  // 내 차례가 아니면 이유를 보여 준다(버튼을 이유 없이 숨기지 않는다, 설계 6.2)
  return (
    <div className={s.actionBar}>
      <span className={s.muted}>
        {doc.status === 'INPROGRESS' && p.current && <>현재 결재자: <EmpName id={p.current.empId} /> · 대기 중</>}
        {p.sourceRejected && <>{SOURCE_TEXT[doc.sourceType] ?? '원본'} 문서는 반려가 최종입니다.{' '}
          {doc.sourceType === 'ATTENDANCE' && doc.sourceId != null
            ? <Link to={requestHref(doc.sourceId)}>원래 화면에서 새로 신청 →</Link>
            : doc.sourceType === 'LEAVE'
              ? <Link to={APPLY_PATH}>원래 화면에서 새로 신청 →</Link>
              : '원래 화면에서 새로 신청해 주세요.'}</>}
        {p.directRejected && <>반려된 문서입니다. 제목을 고쳐 다시 올릴 수 있습니다.</>}
        {doc.status === 'APPROVED' && <>결재가 끝난 문서입니다.</>}
        {doc.status === 'CANCEL' && <>회수된 문서입니다.</>}
      </span>
      <span className={s.spacer} />
      {p.directRejected && <ResubmitButton doc={doc} />}
      {p.canCancel && (
        <ConfirmAction
          title={`${shortNo(doc.docNo)} 문서를 회수합니다`}
          confirmLabel="회수"
          danger
          onConfirm={() => run({ kind: 'cancel' }, '회수 완료')}
          trigger={(open) => <Button onClick={open} disabled={action.isPending}>회수</Button>}
        >
          아직 아무도 결재하지 않은 문서입니다. 회수하면 결재선 {doc.approvalLines.length}명에게서 문서가 내려가고 되돌릴 수 없습니다.
        </ConfirmAction>
      )}
    </div>
  );
}

/** 문서 상세(설계 6.2): 헤더, 출처, 결재선, 속성, 이력, 액션 바. */
export function DocumentView({ id, onProcessed }: { id: number; onProcessed?: (id: number) => void }) {
  const meId = useMyId();
  const q = useDocument(id);
  if (q.isPending) return <div className={s.detail}><Skeleton active={false} paragraph={{ rows: 8 }} /></div>;
  if (q.isError) return <div className={s.detail}><ErrorPanel error={q.error} onRetry={() => q.refetch()} /></div>;
  const doc = q.data;
  const p = documentPermissions(doc, meId);
  return (
    <div className={s.detailWrap}>
      <div className={s.detail}>
        <div className={s.head}>
          <span className="nx-mono">{doc.docNo}</span>
          <span className={s.form}>{formTypeLabel(doc.formType)}</span>
          <StatusBadge domain="DocumentStatus" value={doc.status} />
          {p.myTurn && <StatusBadge domain="MyTurn" value="MY_TURN" />}
        </div>
        <h2 className={s.title}>{doc.title}</h2>
        <div className={s.meta}>
          기안 <EmpName id={doc.drafterId} /> · <span className="nx-num">{formatDateTime(doc.draftedAt)}</span>
          {doc.completedAt && <> · 완료 <span className="nx-num">{formatDateTime(doc.completedAt)}</span></>}
        </div>

        {doc.sourceType === 'AUTOMATION' && (
          <div className={s.aiBand}>
            <AiMark label="자동화로 생성됨" />
            {doc.sourceLabel && <span>{doc.sourceLabel}</span>}
            {doc.sourceId != null && <span className="nx-mono">run #{doc.sourceId}</span>}
            {doc.sourceId != null && <Link to={`/automation/runs/${doc.sourceId}`} style={{ marginLeft: 'auto' }}>원본 실행 보기 ↗</Link>}
          </div>
        )}
        {(doc.sourceType === 'LEAVE' || doc.sourceType === 'ATTENDANCE') && (
          <div className={s.source}>
            <StatusBadge domain="SourceType" value={doc.sourceType} />
            <span>{doc.sourceLabel ?? SOURCE_TEXT[doc.sourceType]} <span className="nx-num">#{doc.sourceId}</span>에서 생성된 문서입니다</span>
            {/* 근태·휴가 신청 상세는 본인 API(/me/requests)뿐이라 기안자에게만 연결한다(frontend-backend-gaps.md C) */}
            {doc.sourceId != null && doc.drafterId === meId && (
              <Link to={doc.sourceType === 'ATTENDANCE' ? requestHref(doc.sourceId) : leaveRequestHref(doc.sourceId)}
                    style={{ marginLeft: 'auto' }}>원본 신청 보기 →</Link>
            )}
          </div>
        )}

        <section className={s.section}>
          <h3 className={s.h3}>결재선</h3>
          <ApprovalLine doc={doc} meId={meId} />
        </section>
        {p.canEditReferrers && (
          <section className={s.section}>
            <h3 className={s.h3}>참조자</h3>
            <ReferrerEditor doc={doc} />
          </section>
        )}
        <Attributes doc={doc} />
        <section className={s.section}>
          <h3 className={s.h3}>이력</h3>
          <Histories list={doc.histories} />
        </section>
      </div>
      <ActionBar key={doc.documentId} doc={doc} p={p} onProcessed={onProcessed} />
    </div>
  );
}
