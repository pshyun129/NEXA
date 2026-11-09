import type { LucideIcon } from 'lucide-react';
import type { Tone } from '@/styles/tokens';
import * as I from '@/ui/icons';

// 설계 4장 매핑 표의 단일 원천. 화면은 <StatusBadge domain value />만 쓴다.
export type BadgeTone = Tone | 'brand';
export interface StatusSpec {
  label: string;
  tone: BadgeTone;
  filled?: boolean;        // 사람이 지금 행동해야 하는 상태만(내 차례, WAITING, SUBMITTING)
  icon?: LucideIcon;
  dashed?: boolean;        // LineAction SKIPPED
  strongBorder?: boolean;  // LeaveRequest CANCELLED
  spin?: boolean;          // RunStatus RUNNING
}

const m = <K extends string>(specs: Record<K, StatusSpec>) => specs;

export const statusMap = {
  DocumentStatus: m({
    INPROGRESS: { label: '진행중', tone: 'info', icon: I.CircleDot },
    APPROVED: { label: '승인', tone: 'success', icon: I.Check },
    REJECTED: { label: '반려', tone: 'danger', icon: I.X },
    CANCEL: { label: '회수', tone: 'neutral', icon: I.Undo2 },
  }),
  MyTurn: m({
    MY_TURN: { label: '내 차례', tone: 'brand', filled: true, icon: I.PenLine },
  }),
  LineAction: m({
    PENDING: { label: '대기', tone: 'neutral', icon: I.Circle },
    AGREE: { label: '승인', tone: 'success', icon: I.Check },
    DISAGREE: { label: '반려', tone: 'danger', icon: I.X },
    SKIPPED: { label: '전결 생략', tone: 'neutral', icon: I.SkipForward, dashed: true },
  }),
  SourceType: m({
    DIRECT: { label: '직접 기안', tone: 'neutral', icon: I.FileText },
    LEAVE: { label: '휴가', tone: 'neutral', icon: I.Palmtree },
    ATTENDANCE: { label: '근태', tone: 'neutral', icon: I.Clock },
    AUTOMATION: { label: '자동화', tone: 'ai', icon: I.Sparkles },
  }),
  AttendanceRequestStatus: m({
    PENDING: { label: '결재 대기', tone: 'info' },
    APPROVED: { label: '승인', tone: 'success' },
    REJECTED: { label: '반려', tone: 'danger' },
    CANCELED: { label: '회수', tone: 'neutral' },
  }),
  AnomalyType: m({
    LATE: { label: '지각', tone: 'warning', icon: I.ClockAlert },
    EARLY_LEAVE: { label: '조퇴', tone: 'warning', icon: I.LogOut },
    MISSING_CLOCK: { label: '출퇴근 누락', tone: 'danger', icon: I.CircleAlert },
    UNDER_STD_HOURS: { label: '소정근로 미달', tone: 'neutral', icon: I.Hourglass },
    UNAPPROVED_OT: { label: '미승인 초과근로', tone: 'danger', icon: I.TimerOff },
    SHORT_BREAK: { label: '휴게 부족', tone: 'neutral' },
    WORK_ON_LEAVE: { label: '휴가 중 출근', tone: 'neutral' },
  }),
  HistoryAction: m({
    SUBMIT: { label: '상신', tone: 'neutral' },
    RESUBMIT: { label: '재기안', tone: 'neutral' },
    APPROVE: { label: '승인', tone: 'success' },
    FINAL_APPROVE: { label: '전결', tone: 'success' },
    REJECT: { label: '반려', tone: 'danger' },
    CANCEL: { label: '회수', tone: 'neutral' },
    APPROVER_ADD: { label: '결재자 추가', tone: 'info' },
    APPROVER_REMOVE: { label: '결재자 제외', tone: 'info' },
  }),
  LeaveRequestStatus: m({
    SUBMITTING: { label: '처리 확인 중', tone: 'warning', filled: true, icon: I.RefreshCw },
    DRAFT: { label: '작성 중', tone: 'neutral' },
    SUBMITTED: { label: '결재 중', tone: 'info' },
    APPROVED: { label: '승인', tone: 'success' },
    REJECTED: { label: '반려', tone: 'danger' },
    WITHDRAWN: { label: '회수', tone: 'neutral' },
    CANCELLED: { label: '취소', tone: 'neutral', icon: I.Ban, strongBorder: true },
  }),
  RunStatus: m({
    QUEUED: { label: '대기', tone: 'neutral', icon: I.Clock },
    RUNNING: { label: '실행 중', tone: 'ai', icon: I.Loader, spin: true },
    WAITING: { label: '확인 대기', tone: 'warning', filled: true, icon: I.UserCheck },
    SUCCEEDED: { label: '완료', tone: 'success' },
    FAILED: { label: '실패', tone: 'danger' },
    CANCELED: { label: '취소', tone: 'neutral' },
  }),
  AsyncSync: m({
    SYNCING: { label: '반영 중', tone: 'info', icon: I.RefreshCw },
    DELAYED: { label: '반영 지연', tone: 'neutral', icon: I.Clock3 },
  }),
  RunOutcome: m({
    ENDED_REJECTED: { label: '반려로 종료', tone: 'neutral', icon: I.Ban },
    ENDED_NEEDS_CHECK: { label: '확인 필요로 종료', tone: 'neutral', icon: I.CircleHelp },
  }),
  Severity: m({
    BLOCK: { label: '적재 불가', tone: 'danger', icon: I.OctagonX },
    WARN: { label: '확인 필요', tone: 'warning', icon: I.TriangleAlert },
    FIX: { label: '자동 정리', tone: 'info', icon: I.Wand },
  }),
  /** 이관 매핑 추천 출처. LLM은 화면에서 AiMark(+추천 이유 툴팁)로 그린다(설계 6.7 AI 표식) */
  SuggestedBy: m({
    SYNONYM: { label: '이름 일치', tone: 'neutral' },
    LLM: { label: 'AI 추천', tone: 'ai', icon: I.Sparkles },
    USER: { label: '직접 지정', tone: 'brand', icon: I.User },
  }),
  MigrationStatus: m({
    DRAFT: { label: '작성 중', tone: 'neutral' },
    LOADED: { label: '적재됨', tone: 'info' },
    COMPLETED: { label: '전환 완료', tone: 'success' },
  }),
  BatchStatus: m({
    LOADING: { label: '적재 중', tone: 'info' },
    LOADED: { label: '적재됨', tone: 'success' },
    FAILED: { label: '실패', tone: 'danger' },
    ROLLED_BACK: { label: '되돌림', tone: 'neutral' },
  }),
  /** 이관 기준정보 계획(GET /migrations/{id}/references) */
  ReferencePlanStatus: m({
    NEW: { label: '새로 만듦', tone: 'info' },
    EXISTS: { label: '이미 있음', tone: 'neutral' },
    AMBIGUOUS: { label: '고를 수 없음', tone: 'danger' },
  }),
  EmploymentStatus: m({
    EMPLOYED: { label: '재직', tone: 'success' },
    ON_LEAVE: { label: '휴직', tone: 'warning' },
    TERMINATED: { label: '퇴직', tone: 'neutral' },
  }),
} as const;

export type StatusDomain = keyof typeof statusMap;

/** 모르는 값은 neutral 옅은 배지 + 원문 코드(설계 4장 fallback) */
export function resolveStatus(domain: StatusDomain, value: string): StatusSpec {
  const table = statusMap[domain] as Record<string, StatusSpec>;
  return table[value] ?? { label: value, tone: 'neutral' };
}
