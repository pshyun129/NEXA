import { useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { Dropdown, Segmented, Tooltip } from 'antd';
import { useQueryClient } from '@tanstack/react-query';
import { Icon } from '@/ui/Icon';
import { ChevronLeft, ChevronRight, Hexagon, LogOut } from '@/ui/icons';
import { Counter } from '@/ui/primitives';
import { useSession } from '@/auth/session';
import { authApi } from '@/auth/authApi';
import { useCan } from '@/auth/useCan';
import { adminNav, allNav, userNav, type NavItem } from './nav';
import { useViewport } from './useViewport';
import { useTodoCounts } from './useTodoCounts';
import { ClockChip } from '@/attendance/Clock';
import { ShortcutHelp } from './ShortcutHelp';
import s from './shell.module.css';

const COLLAPSE_KEY = 'nx.sidebarCollapsed';

function readCollapsed() {
  try { return localStorage.getItem(COLLAPSE_KEY) === '1'; } catch { return false; }
}

export function AppShell() {
  const { band } = useViewport();
  const location = useLocation();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { me, clear } = useSession();
  const canAdmin = useCan('admin.view');

  // 모드는 URL에서 계산한다(새로고침해도 유지, 설계 5장)
  const adminMode = location.pathname === '/admin' || location.pathname.startsWith('/admin/');
  const [manualCollapsed, setManualCollapsed] = useState(readCollapsed);
  const autoCollapsed = band !== 'wide';
  const collapsed = autoCollapsed || manualCollapsed;
  const counts = useTodoCounts(adminMode);

  useEffect(() => {
    try { localStorage.setItem(COLLAPSE_KEY, manualCollapsed ? '1' : '0'); } catch { /* noop */ }
  }, [manualCollapsed]);

  // 새 WAITING이 생기면 탭 제목에 표시(설계 5장)
  useEffect(() => {
    document.title = counts.waiting ? `(${counts.waiting}) 확인 필요 · NEXA` : 'NEXA';
  }, [counts.waiting]);

  if (band === 'narrow') {
    return <div className={s.narrow}>PC(1024px 이상)에서 이용해 주세요.</div>;
  }

  const nav = adminMode ? adminNav : userNav;
  const current = [...allNav]
    .sort((a, b) => b.to.length - a.to.length)
    .find((n) => (n.to === '/' ? location.pathname === '/' : location.pathname === n.to || location.pathname.startsWith(`${n.to}/`)));

  const logout = async () => {
    await authApi.logout();
    qc.clear();
    clear();
    navigate('/login', { replace: true });
  };

  const countOf = (item: NavItem) =>
    item.count === 'approval' ? counts.approval : item.count === 'waiting' ? counts.waiting : undefined;

  return (
    <div className={s.root}>
      <aside className={[s.sidebar, collapsed ? s.collapsed : '', adminMode ? `${s.admin} nx-sidebar--admin` : ''].join(' ')}
             aria-label="주 메뉴">
        <div className={s.brand}>
          <Icon as={Hexagon} size={20} className={s.brandMark} />
          {!collapsed && 'NEXA'}
        </div>
        {canAdmin && (
          <div className={s.mode}>
            <Segmented
              block
              value={adminMode ? 'admin' : 'user'}
              options={[{ label: '사원', value: 'user' }, { label: '관리', value: 'admin' }]}
              onChange={(v) => navigate(v === 'admin' ? '/admin' : '/')}
            />
          </div>
        )}
        <nav className={s.nav}>
          {nav.map((item) => {
            const n = countOf(item);
            const link = (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.to === '/' || item.to === '/admin'}
                className={({ isActive }) => [s.item, isActive ? s.itemActive : ''].join(' ')}
              >
                <Icon as={item.icon} size={20} />
                <span className={s.itemLabel}>{item.label}</span>
                {n !== undefined && n > 0 && (
                  <span className={s.itemCount}><Counter value={n} tone={item.count === 'waiting' ? 'warning' : 'brand'} /></span>
                )}
              </NavLink>
            );
            return collapsed ? <Tooltip key={item.to} title={item.label} placement="right">{link}</Tooltip> : link;
          })}
        </nav>
        {!autoCollapsed && (
          <div className={s.foot}>
            <button type="button" className={s.collapseBtn} onClick={() => setManualCollapsed((v) => !v)}
                    aria-label={collapsed ? '메뉴 펼치기' : '메뉴 접기'}>
              <Icon as={collapsed ? ChevronRight : ChevronLeft} size={16} />
              <span className={s.collapseLabel}>접기</span>
            </button>
          </div>
        )}
      </aside>

      <div className={s.main}>
        <header className={s.topbar}>
          <div className={s.crumbs}>
            {adminMode ? '관리' : '사원'} / <b>{current?.label ?? ''}</b>
          </div>
          <div className={s.topRight}>
            {!adminMode && <ClockChip />}
            {!adminMode && counts.approval !== undefined && (
              <NavLink to="/approvals" className={`${s.todo} nx-hit24`}>
                결재 <Counter value={counts.approval} tone="brand" label="결재" />
              </NavLink>
            )}
            {!adminMode && counts.waiting !== undefined && (
              <NavLink to="/automation" className={`${s.todo} nx-hit24`}>
                확인 대기 <Counter value={counts.waiting} tone="warning" label="확인 대기" />
              </NavLink>
            )}
            <Dropdown
              trigger={['click']}
              menu={{ items: [{ key: 'logout', label: '로그아웃', icon: <Icon as={LogOut} size={16} />, onClick: logout }] }}
            >
              <button type="button" className={s.profile} aria-label="프로필 메뉴">
                <span className={s.avatar} aria-hidden>{me?.name?.slice(0, 1) ?? '?'}</span>
                <span style={{ textAlign: 'left', lineHeight: '16px' }}>
                  <div>{me?.name}</div>
                  {me?.deptName && <div className={s.profileSub}>{me.deptName}</div>}
                </span>
              </button>
            </Dropdown>
          </div>
        </header>
        <main className={`${s.content} nx-scroll`}>
          <Outlet />
        </main>
        <ShortcutHelp />
      </div>
    </div>
  );
}
