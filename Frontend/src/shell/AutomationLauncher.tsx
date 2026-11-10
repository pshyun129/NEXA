import { useEffect } from 'react';
import { useMyPendingRuns } from '@/api/ai';
import { useAutomationUi } from '@/automation/automationStore';
import { Icon } from '@/ui/Icon';
import { Workflow } from '@/ui/icons';
import { Kbd, modKey } from '@/ui/primitives';
import s from './launcher.module.css';

/**
 * 탑바 [업무 자동화] 버튼. 자동화 관련 화면(실행 · 내 워크플로 · 빌더 · 실행 기록 · 확인 대기)은 모두 이 버튼 하나로 연다.
 * 확인을 기다리는 실행이 있으면 버튼에 숫자 알림이 붙는다. Ctrl(⌘)+J로도 연다.
 */
export function AutomationLauncher() {
  const openHub = useAutomationUi((st) => st.openHub);
  const isOpen = useAutomationUi((st) => st.open);
  const close = useAutomationUi((st) => st.close);
  const pending = useMyPendingRuns();
  const waiting = pending.data?.length ?? 0;

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && !e.shiftKey && e.key.toLowerCase() === 'j') {
        e.preventDefault();
        if (useAutomationUi.getState().open) close(); else openHub(waiting ? { kind: 'home', tab: 'waiting' } : undefined);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [openHub, close, waiting]);

  return (
    <button type="button" className={`${s.trigger} ${isOpen ? s.on : ''}`} aria-haspopup="dialog" aria-expanded={isOpen}
            onClick={() => openHub(waiting ? { kind: 'home', tab: 'waiting' } : undefined)}
            aria-label={waiting ? `업무 자동화, 확인 대기 ${waiting}건` : '업무 자동화'}>
      <Icon as={Workflow} size={16} />
      <span className={s.triggerLabel}>업무 자동화</span>
      {waiting > 0 && <span className={s.badge} aria-hidden>{waiting}</span>}
      <Kbd>{modKey}+J</Kbd>
    </button>
  );
}
