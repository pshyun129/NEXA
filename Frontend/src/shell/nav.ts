import type { LucideIcon } from 'lucide-react';
import {
  House, CalendarClock, CalendarDays, FileSignature, Wallet, Network, Users, DatabaseZap,
} from '@/ui/icons';

export interface NavItem {
  to: string;
  label: string;
  icon: LucideIcon;
  /** 사이드바 옆 숫자 */
  count?: 'approval' | 'waiting';
}

// 사원 메뉴(요구사항 2장). 업무 자동화는 탑바 [업무 자동화] 버튼 하나로 연다(사이드바에 두지 않는다)
export const userNav: NavItem[] = [
  { to: '/', label: '홈', icon: House },
  { to: '/attendance', label: '근태', icon: CalendarClock },
  { to: '/leave', label: '휴가', icon: CalendarDays },
  { to: '/approvals', label: '전자결재', icon: FileSignature, count: 'approval' },
  { to: '/pay', label: '급여', icon: Wallet },
  { to: '/org', label: '조직도', icon: Network },
];

// 관리자 메뉴(P2 화면은 넣지 않는다, 설계 9장)
export const adminNav: NavItem[] = [
  { to: '/admin', label: '관리 홈', icon: House },
  { to: '/admin/hr', label: '인사', icon: Users },
  { to: '/admin/pay', label: '급여', icon: Wallet },
  { to: '/admin/migration', label: '데이터 이관', icon: DatabaseZap },
  { to: '/admin/leave', label: '휴가 정책', icon: CalendarDays },
];

export const allNav = [...userNav, ...adminNav];
