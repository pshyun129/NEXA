// 상태가 아닌 enum 라벨(요구사항 5장 라벨 사전). 상태 배지는 statusMap.ts를 쓴다.
import type { Tone } from '@/styles/tokens';

const label = <K extends string>(table: Record<K, string>) => (v: string | null | undefined) =>
  v == null ? '—' : (table as Record<string, string>)[v] ?? v;

export const formTypeLabel = label({
  LEAVE: '휴가 신청', OVERTIME: '초과근무 신청', TIME_FIX: '근무시간 수정', HOLIDAY_SUBST: '휴일대체',
  BUSINESS_TRIP: '출장 신청', GENERAL: '일반 기안',
});

export const lineRoleLabel = label({ APPROVER: '결재', AGREEMENT: '합의', REFERENCE: '참조', VIEWER: '열람' });

export const approvalLevelLabel = label({ TEAM: '팀장 전결', DIVISION: '본부장 전결', COMPANY: '대표이사 결재' });

export const leavePortionLabel = label({ FULL: '종일', AM_HALF: '오전 반차', PM_HALF: '오후 반차' });

/** 휴가 원장 이벤트(leave LedgerEventType) */
export const leaveLedgerLabel = label({
  GRANT: '발생', ADJUST: '조정', RESERVE: '신청 예약', RELEASE: '예약 해제', CONSUME: '사용', RESTORE: '복원',
  EXPIRE: '소멸', REVOKE: '발생 취소', CARRY_OUT: '이월(보냄)', CARRY_IN: '이월(받음)',
});

export const leaveGrantKindLabel = label({ AUTO: '정책 발생', MANUAL: '수동 지급', CARRY_OVER: '이월', COMP: '보상휴가' });

/** 근무 상태 점 색(설계 5장 ClockChip). 모르는 코드는 neutral. */
export function workStatusTone(code: string | null | undefined): Tone {
  switch (code) {
    case 'WORKING':
    case 'CLOCK_IN':
      return 'success';
    case 'LEAVE':
      return 'info';
    default:
      return 'neutral';
  }
}

export const requestTypeLabel = label({ TIME_FIX: '근무시간 수정', OVERTIME: '초과근무', HOLIDAY_SUBST: '휴일대체' });

export const itemActionLabel = label({ ADD: '추가', MODIFY: '수정', DELETE: '삭제' });

/** 근무 상태 코드(요구사항 5장). 서버 statusName이 있으면 그것을 우선한다. */
export const workStatusLabel = label({
  WORKING: '업무', OFF_WORKING: '업무종료', LEAVE: '휴가', CLOCK_IN: '출근', CLOCK_OUT: '퇴근', CLOCK_OUT_CANCEL: '퇴근취소',
});

export const workStatusSourceLabel = label({ SELF: '본인', ADMIN: '관리자', APPROVAL: '결재 승인' });
