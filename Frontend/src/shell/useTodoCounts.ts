import { useMyPendingRuns } from '@/api/ai';
import { useDocumentCount } from '@/api/approval';

/**
 * 탑바·사이드바 카운터(설계 7.3 전역 카운트): 60초 + 포커스 재조회, 숨겨진 탭과 관리 모드에서는 중단.
 * approval = 내 차례 결재 건수(GET /documents/count?box=PENDING), waiting = 자동화 확인 대기.
 * 불러오기 전이나 실패하면 undefined(카운터 숨김, 0은 거짓 정보).
 */
export function useTodoCounts(adminMode: boolean) {
  const approval = useDocumentCount({ box: 'PENDING' }, !adminMode);
  const waiting = useMyPendingRuns(!adminMode);
  return {
    approval: approval.isSuccess ? approval.data : undefined,
    waiting: waiting.isSuccess ? waiting.data.length : undefined,
  };
}
