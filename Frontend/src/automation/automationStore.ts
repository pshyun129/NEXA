import { create } from 'zustand';

/** 업무 자동화 창의 화면: 목록(탭) · 실행 입력 · 빌더 · 실행 화면 */
export type HubView =
  | { kind: 'home'; tab?: HubTab }
  | { kind: 'start'; workflowId: number }
  | { kind: 'builder'; workflowId: number }
  | { kind: 'run'; runId: number };
export type HubTab = 'run' | 'mine' | 'history' | 'waiting';

interface AutomationUi {
  /** 큰 모달이 열려 있는지 */
  open: boolean;
  view: HubView;
  /** 오른쪽 위 작은 진행 창에 보여 주는 실행 */
  mini: { runId: number; workflowId: number } | null;
  openHub: (view?: HubView) => void;
  setView: (view: HubView) => void;
  close: () => void;
  /** 실행을 시작하면 큰 모달을 닫고 작은 진행 창으로 바꾼다 */
  minimize: (runId: number, workflowId: number) => void;
  closeMini: () => void;
}

/**
 * 업무 자동화는 상단 [업무 자동화] 버튼 하나로 연다. 화면 어디서든(홈 카드, 결재 문서의 "원본 실행 보기" 등)
 * 이 저장소로 같은 창을 연다. 라우트(/automation, /admin/builder)는 주소를 직접 친 경우에만 쓴다.
 */
export const useAutomationUi = create<AutomationUi>((set) => ({
  open: false,
  view: { kind: 'home' },
  mini: null,
  openHub: (view) => set({ open: true, view: view ?? { kind: 'home' } }),
  setView: (view) => set({ view }),
  close: () => set({ open: false }),
  minimize: (runId, workflowId) => set({ open: false, mini: { runId, workflowId } }),
  closeMini: () => set({ mini: null }),
}));

/** 컴포넌트 밖(링크 핸들러 등)에서 여는 지름길 */
export const openAutomation = (view?: HubView) => useAutomationUi.getState().openHub(view);
