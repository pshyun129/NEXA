import { isAdmin, useSession } from './session';

// 권한 판정을 한 곳에 모은다(직책 기반 권한 전환 대비, 요구사항 1.5).
export type Action = 'admin.view' | 'builder.edit' | 'migration.run';

export function useCan(action: Action): boolean {
  const me = useSession((s) => s.me);
  switch (action) {
    case 'admin.view':
    case 'builder.edit':
    case 'migration.run':
      return isAdmin(me);
    default:
      return false;
  }
}
