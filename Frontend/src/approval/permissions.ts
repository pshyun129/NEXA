import type { ApprovalLineItem, DocumentDetail } from '@/api/approval';

const isApproverRole = (l: ApprovalLineItem) => l.role === 'APPROVER' || l.role === 'AGREEMENT';

/** 결재자·합의자를 서버와 같은 순서(step → lineId)로 정렬한다(ApprovalServiceImplV1.approvers). */
export function orderedApprovers(doc: DocumentDetail): ApprovalLineItem[] {
  return doc.approvalLines.filter(isApproverRole).sort((a, b) => a.step - b.step || a.lineId - b.lineId);
}

/** 현재 차례: 진행중 문서에서 정렬 순서상 첫 PENDING 결재자·합의자(서버 getMyTurn과 같다). */
export function currentLine(doc: DocumentDetail): ApprovalLineItem | null {
  if (doc.status !== 'INPROGRESS') return null;
  return orderedApprovers(doc).find((l) => l.action === 'PENDING') ?? null;
}

/**
 * 버튼 노출 조건(요구사항 3.4 표)을 한 곳에서 계산한다.
 * 서버 검증(ApprovalServiceImplV1)과 같은 규칙이고, 최종 판정은 서버가 한다.
 */
export function documentPermissions(doc: DocumentDetail, meId: number | null) {
  const approvers = orderedApprovers(doc);
  const current = currentLine(doc);
  const myTurn = current != null && current.empId === meId;
  const isDrafter = doc.drafterId === meId;
  // 전결: 내 차례 && 결재(APPROVER) && 내 뒤에 아직 남은 규정상(required) 결재자가 없음
  const canFinalApprove = myTurn && current!.role === 'APPROVER'
    && !approvers.some((l) => l !== current && l.action === 'PENDING' && l.required);
  // 전결 시 생략되는 결재자(확인 모달 본문)
  const skipped = canFinalApprove ? approvers.filter((l) => l !== current && l.action === 'PENDING') : [];
  return {
    current,
    myTurn,
    isDrafter,
    canApprove: myTurn,
    canReject: myTurn,
    canFinalApprove,
    skipped,
    // 회수: 기안자 && 진행중 && 아무도 결재하지 않음
    canCancel: isDrafter && doc.status === 'INPROGRESS' && approvers.every((l) => l.action === 'PENDING'),
    // 출처 문서(휴가·근태·자동화)는 반려가 최종 → 원래 화면에서 새로 신청
    sourceRejected: isDrafter && doc.status === 'REJECTED' && doc.sourceType !== 'DIRECT',
    // 재기안: 기안자 && 반려 && 직접 기안(출처 문서는 서버가 SOURCE_DOCUMENT_NOT_RESUBMITTABLE)
    directRejected: isDrafter && doc.status === 'REJECTED' && doc.sourceType === 'DIRECT',
    // 참조자 추가·제거: 기안자 && 진행중(서버 getEditableDocument와 같은 조건, P1은 참조자만)
    canEditReferrers: isDrafter && doc.status === 'INPROGRESS',
  };
}

export type DocumentPermissions = ReturnType<typeof documentPermissions>;
