import { Switch, Tooltip } from 'antd';
import { useSavePlacements } from '@/api/builder';
import { useNotify } from '@/ui/useNotify';
import s from './builder.module.css';

/** 사원 화면 중 지금 자동화 목록을 쓰는 곳은 업무 자동화(HOME) 하나다(AutomationPage SCREEN) */
const SCREEN = 'HOME';

/**
 * 사원 "업무 자동화" 목록 노출 스위치. 서버는 배포 버전이 있고 placements에 HOME이 있어야 목록에 준다.
 * 다른 화면 코드는 그대로 둔다(덮어쓰기 API라 전체 목록을 보낸다).
 */
export function PlacementToggle({ workflowId, placements, deployed }:
  { workflowId: number; placements: string[]; deployed: boolean }) {
  const notify = useNotify();
  const save = useSavePlacements(workflowId);
  const on = placements.includes(SCREEN);
  const toggle = (next: boolean) => save.mutate(
    next ? [...placements, SCREEN] : placements.filter((p) => p !== SCREEN),
    {
      onSuccess: () => notify.success(next
        ? (deployed ? '사원 업무 자동화 목록에 표시합니다' : '배포하면 사원 업무 자동화 목록에 표시됩니다')
        : '사원 업무 자동화 목록에서 뺐습니다'),
      onError: (e) => notify.error(e, '노출 설정을 바꾸지 못했습니다'),
    },
  );
  const hint = on
    ? (deployed ? '사원 업무 자동화 목록에 보입니다' : '배포하면 사원 업무 자동화 목록에 보입니다')
    : '꺼져 있으면 배포해도 사원에게 보이지 않습니다';
  return (
    <Tooltip title={hint}>
      <label className={s.placement}>
        <Switch size="small" checked={on} loading={save.isPending} onChange={toggle} aria-label="사원 업무 자동화 목록에 노출" />
        <span className={on ? undefined : s.muted}>사원에게 노출</span>
      </label>
    </Tooltip>
  );
}
