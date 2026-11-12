import { Navigate, useLocation } from 'react-router-dom';
import type { HubView } from '@/automation/automationStore';

/** 주소 → 업무 자동화 창 화면. 예전 주소(북마크·알림 링크)를 그대로 살린다 */
export function viewOfPath(pathname: string): HubView {
  const run = pathname.match(/^\/automation\/runs\/(\d+)/);
  if (run) return { kind: 'run', runId: Number(run[1]) };
  const start = pathname.match(/^\/automation\/workflows\/(\d+)/);
  if (start) return { kind: 'start', workflowId: Number(start[1]) };
  const builder = pathname.match(/^\/admin\/builder\/(\d+)/);
  if (builder) return { kind: 'builder', workflowId: Number(builder[1]) };
  if (pathname.startsWith('/admin/builder')) return { kind: 'home', tab: 'mine' };
  return { kind: 'home' };
}

/**
 * 업무 자동화는 이제 별도 화면이 아니라 탑바 [업무 자동화] 버튼으로 여는 큰 창이다.
 * /automation/* · /admin/builder/* 주소로 들어오면 홈으로 옮기고, 옮긴 뒤 같은 창을 연다(AutomationHub가 state를 읽는다).
 */
export function AutomationPage() {
  const { pathname } = useLocation();
  return <Navigate to="/" replace state={{ openAutomation: viewOfPath(pathname) }} />;
}
