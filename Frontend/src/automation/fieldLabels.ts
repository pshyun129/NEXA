// 사람 확인(HITL) 필드 라벨. 서버가 필드 메타를 주지 않아 영수증 3필드만 둔다(설계 6.5, 요구사항 3.7).
// 모르는 키는 키 이름을 그대로 보여 준다.
export const FIELD_LABELS: Record<string, string> = { total: '금액', store: '가맹점', date: '날짜' };
export const fieldLabel = (k: string) => FIELD_LABELS[k] ?? k;
/** 숫자로 보내야 하는 필드(approval 제목 템플릿 {total:,}이 문자열이면 실패한다) */
export const NUMERIC_FIELDS = new Set(['total']);
